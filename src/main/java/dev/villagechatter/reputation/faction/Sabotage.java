package dev.villagechatter.reputation.faction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.network.ChatterNetwork.DialogueLine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The three-way feud (Factions v2, item 2). Villagers, pillagers and piglins each dislike the other two:
 * <ul>
 *   <li>Killing one side's people pleases the other two a little (on top of the usual village/pillager crossover).</li>
 *   <li>Each side has sabotage jobs against the others: thin out their numbers, or loot one of their chests (a village
 *   house, an outpost, a bastion). One job at a time, at most one offer per side per day. Turning a job down costs a
 *   little standing with whoever asked; finishing it and reporting back to any of them earns some.</li>
 * </ul>
 * Villagers offer jobs once they don't distrust you; piglins once they aren't wary of you; pillagers only through
 * their captains (item 3) and only once they tolerate you. The deeds themselves still count against the target
 * (witnesses, law and order) as usual.
 */
public final class Sabotage {
    private Sabotage() {}

    public static final String ASK = "sabotage_ask", ACCEPT = "sabotage_accept", DECLINE = "sabotage_decline", DONE = "sabotage_done";
    private static final long DAY = 24000;
    static final int LINES = 2;

    /**
     * CULL: kill {@code count} of them. LOOT: take something from one of their containers. RAID (pillager captains
     * only): kill {@code count} of a nearby village's people or golems while pillagers see it. GOLD and HEIRLOOM are
     * piglin quests ({@link PiglinQuests}): bring {@code count} gold ingots, or the heirloom planted in a village.
     */
    public enum Kind { CULL, LOOT, RAID, GOLD, HEIRLOOM }

    /** The running job (if any) and the day each side last offered one. */
    public record Book(Optional<Job> job, Map<String, Long> offeredDay) {
        public static final Codec<Book> CODEC = RecordCodecBuilder.create(i -> i.group(
                Job.CODEC.optionalFieldOf("job").forGetter(Book::job),
                Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("offered", Map.of()).forGetter(Book::offeredDay)
        ).apply(i, Book::new));

        public static Book empty() {
            return new Book(Optional.empty(), Map.of());
        }
    }

    /** {@code at}: the village a raid is on (BlockPos.asLong of its anchor bell), else 0. */
    public record Job(Faction issuer, Faction target, Kind kind, int count, int progress, long expires, boolean accepted, long at) {
        public static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.xmap(Faction::valueOf, Faction::name).fieldOf("issuer").forGetter(Job::issuer),
                Codec.STRING.xmap(Faction::valueOf, Faction::name).fieldOf("target").forGetter(Job::target),
                Codec.STRING.xmap(Kind::valueOf, Kind::name).fieldOf("kind").forGetter(Job::kind),
                Codec.INT.fieldOf("count").forGetter(Job::count),
                Codec.INT.fieldOf("progress").forGetter(Job::progress),
                Codec.LONG.fieldOf("expires").forGetter(Job::expires),
                Codec.BOOL.fieldOf("accepted").forGetter(Job::accepted),
                Codec.LONG.optionalFieldOf("at", 0L).forGetter(Job::at)
        ).apply(i, Job::new));

        boolean done() {
            return progress >= count;
        }

        Job progressed() {
            return new Job(issuer, target, kind, count, progress + 1, expires, accepted, at);
        }
    }

    // ------------------------------------------------------------------ who is on which side

    /** The side a living thing belongs to in the feud, if any. */
    public static Optional<Faction> sideOf(LivingEntity e) {
        if (e instanceof Villager v && !v.isBaby()) return Optional.of(Faction.VILLAGE);
        if (e instanceof IronGolem golem && !golem.isPlayerCreated()) return Optional.of(Faction.VILLAGE);
        if (dev.villagechatter.life.GuardTrust.isGuard(e)) return Optional.of(Faction.VILLAGE);
        if (e instanceof AbstractIllager) return Optional.of(Faction.PILLAGER);
        if (e instanceof AbstractPiglin) return Optional.of(Faction.PIGLIN);
        return Optional.empty();
    }

    static List<Faction> rivals(Faction f) {
        List<Faction> out = new ArrayList<>(List.of(Faction.VILLAGE, Faction.PILLAGER, Faction.PIGLIN));
        out.remove(f);
        return out;
    }

    /** Whether this side would offer this player work at all (standing high enough). */
    static boolean trusts(ServerPlayer player, Faction f) {
        return switch (f) {
            case VILLAGE -> FactionBook.village(player) > -20;
            case PIGLIN -> FactionBook.piglin(player) > ChatterConfig.PIGLIN_DISLIKED_AT.get();
            case PILLAGER -> FactionBook.tier(FactionBook.pillagerSeen(player)).ordinal() >= Factions.Tier.TOLERATED.ordinal();
        };
    }

    // ------------------------------------------------------------------ the feud on kills

    /** Killing one side's people: the two rival sides think a little better of you. */
    public static void onDeath(LivingDeathEvent event) {
        if (!FactionBook.enabled() || event.getEntity().level().isClientSide()) return;
        ServerPlayer player = dev.villagechatter.life.law.Blame.culprit(event.getSource());
        if (player == null || player.isCreative()) return;
        Optional<Faction> side = sideOf(event.getEntity());
        if (side.isEmpty()) return;
        // the village/pillager crossover already exists (FactionBook.onDeath); add only the piglin edges of the triangle
        double feud = ChatterConfig.FEUD_GAIN.get();
        switch (side.get()) {
            case PIGLIN -> {
                FactionBook.adjust(player, Faction.VILLAGE, feud);
                FactionBook.adjust(player, Faction.PILLAGER, feud);
            }
            case VILLAGE, PILLAGER -> FactionBook.adjust(player, Faction.PIGLIN, feud);
        }
        progress(player, side.get(), Kind.CULL);
        if (side.get() == Faction.VILLAGE) raidKill(player, event.getEntity());
    }

    // ------------------------------------------------------------------ jobs: dialogue

    static Book book(ServerPlayer player) {
        return player.getData(ChatterAttachments.SABOTAGE);
    }

    static void save(ServerPlayer player, Optional<Job> job, Map<String, Long> offered) {
        player.setData(ChatterAttachments.SABOTAGE, new Book(job, offered));
    }

    static long day(ServerPlayer player) {
        return player.server.overworld().getGameTime() / DAY;
    }

    /** The job, dropped first if it ran out of time. */
    public static Optional<Job> job(ServerPlayer player) {
        Book b = book(player);
        if (b.job().isPresent() && player.server.overworld().getGameTime() > b.job().get().expires()) {
            save(player, Optional.empty(), b.offeredDay());
            return Optional.empty();
        }
        return b.job();
    }

    /** Options a member of {@code issuer} adds to a conversation: ask for work, or report a job done. */
    public static List<String> options(ServerPlayer player, Faction issuer) {
        if (!FactionBook.enabled() || !ChatterConfig.SABOTAGE_JOBS.get()) return List.of();
        Optional<Job> job = job(player);
        if (job.isPresent()) return job.get().issuer() == issuer && ready(player, job.get()) ? List.of(DONE) : List.of();
        if (!trusts(player, issuer)) return List.of();
        return offersToday(player, issuer) < (issuer == Faction.PIGLIN ? 2 : 1) ? List.of(ASK) : List.of();
    }

    /** How many jobs this side has offered today (piglins offer two a day, the others one). */
    static int offersToday(ServerPlayer player, Faction issuer) {
        Map<String, Long> offered = book(player).offeredDay();
        long today = day(player);
        int n = 0;
        for (String key : List.of(issuer.name(), issuer.name() + "#2")) {
            Long d = offered.get(key);
            if (d != null && d >= today) n++;
        }
        return n;
    }

    /** Whether the job can be reported done now. Gold and the heirloom are handed over, so they must be on you. */
    static boolean ready(ServerPlayer player, Job job) {
        if (!job.accepted()) return false;
        return switch (job.kind()) {
            case GOLD -> PiglinQuests.hasGold(player, job.count());
            case HEIRLOOM -> PiglinQuests.hasHeirloom(player);
            default -> job.done();
        };
    }

    /** @return true if this was a sabotage action (handled here). */
    public static boolean handle(ServerPlayer player, LivingEntity speaker, String action) {
        if (!action.startsWith("sabotage_")) return false;
        Optional<Faction> side = sideOf(speaker);
        if (side.isEmpty()) return true;
        Faction issuer = side.get();
        Book b = book(player);
        switch (action) {
            case ASK -> {
                if (!options(player, issuer).contains(ASK)) return true;
                Job job = roll(player, issuer);
                Map<String, Long> offered = new HashMap<>(b.offeredDay());
                offered.put(offersToday(player, issuer) == 0 ? issuer.name() : issuer.name() + "#2", day(player));
                save(player, Optional.of(job), offered);
                say(player, speaker, "quest", offerLine(speaker, job), List.of(ACCEPT, DECLINE));
            }
            case ACCEPT -> {
                Optional<Job> job = job(player);
                if (job.isEmpty() || job.get().accepted() || job.get().issuer() != issuer) return true;
                Job j = job.get();
                save(player, Optional.of(new Job(j.issuer(), j.target(), j.kind(), j.count(), 0, j.expires(), true, j.at())), b.offeredDay());
                say(player, speaker, "quest", line(speaker, issuer, "accept"), List.of(DialogueAction.GOODBYE));
            }
            case DECLINE -> {
                Optional<Job> job = job(player);
                if (job.isEmpty() || job.get().accepted()) return true;
                save(player, Optional.empty(), b.offeredDay());
                FactionBook.adjust(player, issuer, -ChatterConfig.SABOTAGE_DECLINE_LOSS.get());
                say(player, speaker, "quest", line(speaker, issuer, "decline"), List.of(DialogueAction.GOODBYE));
            }
            case DONE -> {
                Optional<Job> job = job(player);
                if (job.isEmpty() || !ready(player, job.get()) || job.get().issuer() != issuer) return true;
                Job j = job.get();
                double reward = ChatterConfig.SABOTAGE_REWARD.get();
                String said = "done";
                if (j.kind() == Kind.GOLD) {
                    PiglinQuests.takeGold(player, j.count());
                    reward = j.count() * ChatterConfig.PIGLIN_GOLD_QUEST_GAIN.get();
                    said = "gold_done";
                } else if (j.kind() == Kind.HEIRLOOM) {
                    PiglinQuests.takeHeirloom(player);
                    reward = ChatterConfig.PIGLIN_HEIRLOOM_GAIN.get();
                    said = "heirloom_done";
                }
                save(player, Optional.empty(), b.offeredDay());
                FactionBook.adjust(player, issuer, reward);
                say(player, speaker, "quest", line(speaker, issuer, said), List.of(DialogueAction.GOODBYE));
            }
            default -> { }
        }
        return true;
    }

    /** A job against one of the issuer's rivals: thin them out (2-4) or loot one of their chests. */
    static Job roll(ServerPlayer player, Faction issuer) {
        var random = player.getRandom();
        if (issuer == Faction.PIGLIN) {
            Optional<Job> own = PiglinQuests.roll(player, player.server.overworld().getGameTime());
            if (own.isPresent()) return own.get();
        }
        List<Faction> rivals = rivals(issuer);
        Faction target = rivals.get(random.nextInt(rivals.size()));
        Kind kind = random.nextBoolean() ? Kind.CULL : Kind.LOOT;
        int count = kind == Kind.LOOT ? 1 : target == Faction.VILLAGE ? 1 + random.nextInt(2) : 2 + random.nextInt(3);
        long expires = player.server.overworld().getGameTime() + ChatterConfig.SABOTAGE_DAYS.get() * DAY;
        long at = 0;
        if (issuer == Faction.PILLAGER && target == Faction.VILLAGE && kind == Kind.CULL) {
            // a captain's culling job is a raid on a real village nearby (or, with none about, a chest to rob)
            Optional<BlockPos> village = nearestVillage(player.serverLevel(), player.blockPosition());
            if (village.isPresent()) {
                kind = Kind.RAID;
                count = 3;
                at = village.get().asLong();
            } else {
                kind = Kind.LOOT;
                count = 1;
            }
        }
        return new Job(issuer, target, kind, count, 0, expires, false, at);
    }

    /** The anchor bell of the nearest village within raidRange. */
    static Optional<BlockPos> nearestVillage(ServerLevel level, BlockPos from) {
        return level.getPoiManager().findClosest(t -> t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING), from,
                        ChatterConfig.RAID_RANGE.get(), net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
                .map(bell -> dev.villagechatter.reputation.Villages.bellCluster(level, bell).get(0));
    }

    /**
     * A raid kill: one of the raided village's people or golems, killed by you where a pillager sees it. The pillagers
     * like it (a medium-light gain); the villages, very much not (a heavy loss, witnesses or not).
     */
    static void raidKill(ServerPlayer player, LivingEntity dead) {
        Optional<Job> job = job(player);
        if (job.isEmpty() || job.get().kind() != Kind.RAID || !job.get().accepted() || job.get().done()) return;
        if (!BlockPos.of(job.get().at()).closerThan(dead.blockPosition(), ChatterConfig.RAID_RADIUS.get())) return;
        boolean seen = !dead.level().getEntitiesOfClass(AbstractIllager.class, dead.getBoundingBox().inflate(24),
                p -> p.isAlive() && p.hasLineOfSight(dead)).isEmpty();
        if (!seen) return;
        FactionBook.adjust(player, Faction.PILLAGER, ChatterConfig.RAID_KILL_GAIN.get());
        FactionBook.adjust(player, Faction.VILLAGE, -ChatterConfig.RAID_KILL_LOSS.get());
        progress(player, Faction.VILLAGE, Kind.RAID);
    }

    /** "Those pillagers have been eyeing our fields. Thin them out: three of them." */
    static Component offerLine(LivingEntity speaker, Job job) {
        if (job.kind() == Kind.GOLD) {
            return Component.translatable("villagechatter.piglin.quest.gold." + speaker.getRandom().nextInt(LINES), job.count());
        }
        if (job.kind() == Kind.HEIRLOOM) {
            BlockPos at = BlockPos.of(job.at());
            return Component.translatable("villagechatter.piglin.quest.heirloom." + speaker.getRandom().nextInt(LINES),
                    Math.round(at.getX() / 50.0) * 50, Math.round(at.getZ() / 50.0) * 50);
        }
        if (job.kind() == Kind.RAID && speaker.level() instanceof ServerLevel level) {
            return Component.translatable("villagechatter.sabotage.pillager.raid.village." + speaker.getRandom().nextInt(LINES),
                    job.count(), dev.villagechatter.reputation.Villages.label(level, BlockPos.of(job.at())));
        }
        String base = "villagechatter.sabotage." + job.issuer().name().toLowerCase() + "." + job.kind().name().toLowerCase()
                + "." + job.target().name().toLowerCase() + ".";
        return Component.translatable(base + speaker.getRandom().nextInt(LINES), job.count());
    }

    static Component line(LivingEntity speaker, Faction issuer, String what) {
        return Component.translatable("villagechatter.sabotage." + issuer.name().toLowerCase() + "." + what + "."
                + speaker.getRandom().nextInt(LINES));
    }

    static void say(ServerPlayer player, LivingEntity speaker, String topic, Component line, List<String> options) {
        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), topic, options,
                new ChatterNetwork.Extras(Map.of(), Optional.of(line))));
    }

    // ------------------------------------------------------------------ jobs: progress

    static void progress(ServerPlayer player, Faction target, Kind kind) {
        Optional<Job> job = job(player);
        if (job.isEmpty() || !job.get().accepted() || job.get().done() || job.get().target() != target || job.get().kind() != kind) return;
        Job next = job.get().progressed();
        save(player, Optional.of(next), book(player).offeredDay());
        if (next.done()) {
            player.displayClientMessage(Component.translatable("villagechatter.sabotage.ready",
                    Component.translatable("villagechatter.sabotage.side." + next.issuer().name().toLowerCase())), true);
        }
    }

    private static final Map<UUID, BlockPos> CLICKED = new HashMap<>();
    private static final Map<UUID, Opened> OPENED = new HashMap<>();

    private record Opened(Faction owner, int items) {}

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level().getBlockEntity(event.getPos()) instanceof Container) {
            CLICKED.put(player.getUUID(), event.getPos().immutable());
        }
    }

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = CLICKED.remove(player.getUUID());
        if (pos == null || job(player).isEmpty()) return;
        owner(player.serverLevel(), pos).ifPresent(f -> OPENED.put(player.getUUID(), new Opened(f, count(event.getContainer()))));
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Opened opened = OPENED.remove(player.getUUID());
        if (opened != null && opened.items() > count(event.getContainer())) progress(player, opened.owner(), Kind.LOOT);
    }

    public static void onLogout(net.minecraft.world.entity.player.Player player) {
        CLICKED.remove(player.getUUID());
        OPENED.remove(player.getUUID());
    }

    /** Whose container this is: inside a village, a pillager outpost or a bastion. */
    static Optional<Faction> owner(ServerLevel level, BlockPos pos) {
        if (in(level, pos, BuiltinStructures.PILLAGER_OUTPOST) || in(level, pos, BuiltinStructures.WOODLAND_MANSION)) return Optional.of(Faction.PILLAGER);
        if (in(level, pos, BuiltinStructures.BASTION_REMNANT)) return Optional.of(Faction.PIGLIN);
        if (dev.villagechatter.life.law.Witnesses.village(level, pos).isPresent()) return Optional.of(Faction.VILLAGE);
        return Optional.empty();
    }

    static boolean in(ServerLevel level, BlockPos pos, ResourceKey<Structure> key) {
        var structure = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(key);
        return structure != null && level.structureManager().getStructureWithPieceAt(pos, structure).isValid();
    }

    private static int count(AbstractContainerMenu menu) {
        int n = 0;
        for (var slot : menu.slots) if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) n += slot.getItem().getCount();
        return n;
    }
}

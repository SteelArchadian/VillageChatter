package dev.villagechatter.reputation.faction;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.LivingVillage;
import dev.villagechatter.life.law.CrimeKind;
import dev.villagechatter.life.law.VillageStanding;
import dev.villagechatter.life.law.Witnesses;
import dev.villagechatter.reputation.Standing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Faction standings (see {@link Factions} for the public side). Three layers: a villager's own opinion, a village's
 * (the average of everyone in it, reputation.Standing), and the faction. The village faction is never stored as such:
 * it's a summary of the villages you know (weighted by size and by how recently you saw them) plus a "word on the
 * road" term for deeds away from any village, which fades toward 0. The pillager faction is its own meter.
 */
public final class FactionBook {
    private FactionBook() {}

    public static final double MIN = -100, MAX = 100;
    private static final long DAY = 24000;
    private static final int RECORD_EVERY = 200, HOSTILE_EVERY = 40, WARN_EVERY = 20;
    private static final int GUARD_SIGHT = 20, WARN_RANGE = 12;
    /** Raiders you hit stay fair game (and so do you) for this long. */
    private static final long GRUDGE = 1200;
    private static final long WARN_COOLDOWN = 600;

    /** What one village thought of you when you were last there, how big it was, and when. */
    public record Seen(double score, int size, long seenAt, long firstSeen) {
        static final Codec<Seen> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("score").forGetter(Seen::score),
                Codec.INT.fieldOf("size").forGetter(Seen::size),
                Codec.LONG.fieldOf("seen").forGetter(Seen::seenAt),
                Codec.LONG.fieldOf("first").forGetter(Seen::firstSeen)).apply(i, Seen::new));
    }

    /** A player's faction book. Saved with the player, kept on death. */
    public static final class Book {
        public static final Codec<Book> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Seen.CODEC).fieldOf("villages").forGetter(b -> b.villages),
                Codec.DOUBLE.fieldOf("roaming").forGetter(b -> b.roaming),
                Codec.LONG.fieldOf("roaming_at").forGetter(b -> b.roamingAt),
                Codec.DOUBLE.optionalFieldOf("pillager").forGetter(b -> b.pillager),
                Codec.DOUBLE.optionalFieldOf("piglin").forGetter(b -> b.piglin)).apply(i, Book::new));
        public final Map<String, Seen> villages;
        public double roaming;
        public long roamingAt;
        public Optional<Double> pillager;
        public Optional<Double> piglin;

        public Book() {
            this(Map.of(), 0, 0, Optional.empty(), Optional.empty());
        }

        private Book(Map<String, Seen> villages, double roaming, long roamingAt, Optional<Double> pillager, Optional<Double> piglin) {
            this.villages = new HashMap<>(villages);
            this.roaming = roaming;
            this.roamingAt = roamingAt;
            this.pillager = pillager;
            this.piglin = piglin;
        }
    }

    public static boolean enabled() {
        return ChatterConfig.FACTIONS.get();
    }

    static Book book(ServerPlayer player) {
        return player.getData(ChatterAttachments.FACTIONS);
    }

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    static String key(GlobalPos village) {
        return village.dimension().location() + "|" + village.pos().asLong();
    }

    // ------------------------------------------------------------------ the meters

    /** The word on the road, faded to now. */
    static double roaming(ServerPlayer player) {
        Book book = book(player);
        long age = Math.max(0, now(player) - book.roamingAt);
        return book.roaming * Math.pow(0.5, age / (ChatterConfig.ROAMING_HALF_LIFE_DAYS.get() * DAY));
    }

    public static double village(ServerPlayer player) {
        Book book = book(player);
        long now = now(player);
        double halfLife = ChatterConfig.VILLAGE_RECENCY_DAYS.get() * DAY;
        double sum = 0, weights = 0;
        for (Seen seen : book.villages.values()) {
            double w = Math.max(1, seen.size()) * Math.pow(0.5, Math.max(0, now - seen.seenAt()) / halfLife);
            sum += seen.score() * w;
            weights += w;
        }
        return clamp((weights > 0 ? sum / weights : 0) + roaming(player));
    }

    public static double pillager(ServerPlayer player) {
        return book(player).pillager.orElse((double) ChatterConfig.PILLAGER_START.get());
    }

    /**
     * What pillagers read off you (Factions v2 item 5, revised): while you wear their colours (see {@link BannerLoyalty})
     * they read you as at least Tolerated, whatever your true standing. Take them off and they know you again at once.
     * Patrols and raids ignore the colours: {@link #truce} uses your true standing for them.
     */
    public static double pillagerSeen(ServerPlayer player) {
        double real = pillager(player);
        return disguised(player) ? Math.max(real, ChatterConfig.PILLAGER_TOLERATED.get()) : real;
    }

    /** Wearing the pillagers' colours, with banner loyalty on. */
    public static boolean disguised(ServerPlayer player) {
        return BannerLoyalty.wearing(player);
    }

    public static double piglin(ServerPlayer player) {
        return book(player).piglin.orElse((double) ChatterConfig.PIGLIN_START.get());
    }

    /** Words for the piglin meter: hostile, wary, neutral, friendly, honored. */
    public static String piglinWord(double p) {
        return p < -60 ? "hostile" : p <= ChatterConfig.PIGLIN_DISLIKED_AT.get() ? "wary"
                : p < ChatterConfig.PIGLIN_LIKED_AT.get() ? "neutral" : p < 70 ? "friendly" : "honored";
    }

    public static Factions.Tier tier(double pillager) {
        if (pillager >= ChatterConfig.PILLAGER_ALLY.get()) return Factions.Tier.ALLY;
        if (pillager >= ChatterConfig.PILLAGER_TOLERATED.get()) return Factions.Tier.TOLERATED;
        if (pillager >= ChatterConfig.PILLAGER_WARY.get()) return Factions.Tier.WARY;
        return Factions.Tier.HATED;
    }

    public static void adjust(ServerPlayer player, Factions.Faction faction, double amount) {
        if (amount == 0 || !enabled()) return;
        Book book = book(player);
        if (faction == Factions.Faction.PILLAGER) {
            book.pillager = Optional.of(clamp(pillager(player) + amount));
        } else if (faction == Factions.Faction.PIGLIN) {
            book.piglin = Optional.of(clamp(piglin(player) + amount));
        } else {
            book.roaming = Math.max(MIN * 2, Math.min(MAX * 2, roaming(player) + amount));
            book.roamingAt = now(player);
        }
        player.setData(ChatterAttachments.FACTIONS, book);
    }

    /** A deed one side loves and the other hates: both meters move, opposite ways. */
    static void crossover(ServerPlayer player, double towardPillagers) {
        adjust(player, Factions.Faction.PILLAGER, towardPillagers);
        adjust(player, Factions.Faction.VILLAGE, -towardPillagers / 2);
    }

    private static double clamp(double v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    /** Words for the village meter: hated, distrusted, neutral, liked, honored. */
    static String villageWord(double v) {
        return v < -60 ? "hated" : v < -20 ? "distrusted" : v < 20 ? "neutral" : v < 60 ? "liked" : "honored";
    }

    /** "Villages: Liked (+23) · Pillagers: Wary (-31) · Piglins: Neutral (+4)" for the journal; empty when factions are off. */
    public static Component journalLine(ServerPlayer player) {
        if (!enabled()) return Component.empty();
        double v = village(player), p = pillager(player), g = piglin(player);
        return Component.translatable("villagechatter.faction.journal",
                Component.translatable("villagechatter.faction.village." + villageWord(v)), signed(v),
                Component.translatable("villagechatter.faction.pillager." + tier(p).name().toLowerCase(java.util.Locale.ROOT)), signed(p))
                .append(Component.translatable("villagechatter.faction.journal_piglin",
                        Component.translatable("villagechatter.faction.piglin." + piglinWord(g)), signed(g)));
    }

    public static String signed(double v) {
        long r = Math.round(v);
        return r > 0 ? "+" + r : Long.toString(r);
    }

    // ------------------------------------------------------------------ villages feed the summary

    /** Notes what this village thinks of you now (called while you're in it). */
    public static void record(ServerPlayer player, ServerLevel level, GlobalPos village) {
        // A crime here whose day isn't out: the village knows, but the news hasn't spread (life.law.WitnessMemory).
        if (dev.villagechatter.life.law.WitnessMemory.hasPending(player.server, player.getUUID(), village)) return;
        var score = Standing.villageScore(player, level, village.pos());
        if (score.isEmpty()) return;
        int size = level.getEntitiesOfClass(Villager.class, new AABB(village.pos()).inflate(48), v -> !v.isBaby()).size();
        Book book = book(player);
        long now = now(player);
        Seen old = book.villages.get(key(village));
        book.villages.put(key(village), new Seen(score.getAsDouble(), size, now, old == null ? now : old.firstSeen()));
        player.setData(ChatterAttachments.FACTIONS, book);
    }

    /** When you first saw this village, if ever. */
    public static Optional<Long> firstSeen(ServerPlayer player, GlobalPos village) {
        Seen seen = book(player).villages.get(key(village));
        return seen == null ? Optional.empty() : Optional.of(seen.firstSeen());
    }

    /**
     * Your first visit to a village: your reputation got there before you. It starts at a share of your village
     * standing (on top of any word a hired guard put in).
     */
    public static void firstVisit(ServerPlayer player, ServerLevel level, BlockPos anchor) {
        if (!enabled()) return;
        double head = village(player) * ChatterConfig.VILLAGE_INHERIT.get();
        if (Math.abs(head) >= 0.5) VillageStanding.get(level).add(anchor, player.getUUID(), VillageStanding.Group.ADVOCACY, head);
        Witnesses.village(level, anchor).ifPresent(v -> {
            Book book = book(player);
            book.villages.putIfAbsent(key(v), new Seen(head, 1, now(player), now(player)));
            player.setData(ChatterAttachments.FACTIONS, book);
        });
    }

    // ------------------------------------------------------------------ crimes: severity, witnesses, standing

    /**
     * How much a crime's reputation hit is scaled. More witnesses, bigger hit. High standing cushions petty crimes
     * (people give you the benefit of the doubt) and makes murder hit harder (the fall from grace).
     */
    public static double crimeScale(CrimeKind kind, int witnesses, double standing) {
        double scale = 1 + ChatterConfig.CRIME_WITNESS_WEIGHT.get() * Math.max(0, witnesses - 1);
        scale = Math.min(2, scale);
        double weight = ChatterConfig.CRIME_STANDING_WEIGHT.get();
        if (standing > 0 && weight > 0) {
            double s = Math.min(1, standing / 100) * weight;
            if (kind.murder) scale *= 1 + s;
            else if (kind.slideable || kind.victimHit <= 10) scale *= Math.max(0.5, 1 - s * 0.5);
        }
        return scale;
    }

    // ------------------------------------------------------------------ events

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) return;
        ServerLevel level = player.serverLevel();
        if (player.tickCount % RECORD_EVERY == 0) Witnesses.village(level, player.blockPosition()).ifPresent(v -> record(player, level, v));
        if (player.tickCount % HOSTILE_EVERY == 0 && !player.isCreative()) guardsAttack(player, level);
        if (player.tickCount % WARN_EVERY == 0 && !player.isCreative()) warnArmed(player, level);
    }

    /** Hated by villages everywhere: guards attack on sight. */
    private static void guardsAttack(ServerPlayer player, ServerLevel level) {
        if (village(player) >= ChatterConfig.VILLAGE_HOSTILE_BELOW.get()) return;
        for (Mob guard : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(GUARD_SIGHT),
                m -> m.isAlive() && GuardTrust.isGuard(m) && m.getTarget() == null && m.hasLineOfSight(player))) {
            if (dev.villagechatter.life.law.Companion.isHiredBy(guard, player)) continue;
            guard.setTarget(player);
        }
    }

    private static final Map<UUID, Long> GRUDGES = new HashMap<>(), WARNED = new HashMap<>();

    /** Tolerated or better, and you haven't started anything: raiders outside a raid leave you be. */
    public static boolean truce(ServerPlayer player, Raider raider) {
        if (!enabled() || raider.hasActiveRaid()) return false;
        if (GRUDGES.getOrDefault(player.getUUID(), 0L) > now(player)) return false;
        // a Hero of the Village is the pillagers' enemy while it lasts, whatever else they think of you
        if (ChatterConfig.HERO_ANGERS_PILLAGERS.get() && player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) return false;
        double seen = BannerLoyalty.wild(raider) ? pillager(player) : pillagerSeen(player);
        return tier(seen).ordinal() >= Factions.Tier.TOLERATED.ordinal();
    }

    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Raider raider && event.getNewAboutToBeSetTarget() instanceof ServerPlayer player
                && (truce(player, raider) || RaidOutcome.spared(player))) {
            event.setCanceled(true);
        }
    }

    /** Hitting a raider breaks the truce for a while. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Raider && event.getSource().getEntity() instanceof ServerPlayer player
                && !dev.villagechatter.life.FriendlyFire.forgiven(player, event.getEntity())) grudge(player);
    }

    public static void grudge(ServerPlayer player) {
        GRUDGES.put(player.getUUID(), now(player) + GRUDGE);
    }

    /** Tolerated (not allied): a raider who sees you with a weapon out warns you off. */
    private static void warnArmed(ServerPlayer player, ServerLevel level) {
        if (tier(pillagerSeen(player)) != Factions.Tier.TOLERATED || !armed(player.getMainHandItem())) return;
        long now = now(player);
        if (WARNED.getOrDefault(player.getUUID(), 0L) > now) return;
        for (Raider raider : level.getEntitiesOfClass(Raider.class, player.getBoundingBox().inflate(WARN_RANGE),
                r -> r.isAlive() && r instanceof AbstractIllager && !r.hasActiveRaid() && r.hasLineOfSight(player))) {
            WARNED.put(player.getUUID(), now + WARN_COOLDOWN);
            LivingVillage.say(player, raider, Component.translatable("villagechatter.faction.warn." + level.random.nextInt(5)));
            return;
        }
    }

    public static boolean armed(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem
                || stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES);
    }

    /** Killing raiders: villages like it, pillagers don't. Killing a village's people or protectors: the reverse. */
    public static void onDeath(LivingDeathEvent event) {
        if (!enabled()) return;
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;
        ServerPlayer player = dev.villagechatter.life.law.Blame.culprit(event.getSource());
        if (player == null || player.isCreative()) return;
        // Pillagers only hold it against you once a witness tells another illager (Witnessed): wipe out the band and
        // nobody's left to tell. Villages hear about it either way.
        if (dead instanceof Ravager || dead instanceof Evoker) {
            adjust(player, Factions.Faction.VILLAGE, 1.5);
            Witnessed.offense(player, Factions.Faction.PILLAGER, 3, dead.position(), dead);
        } else if (dead instanceof AbstractIllager) {
            adjust(player, Factions.Faction.VILLAGE, 0.5);
            Witnessed.offense(player, Factions.Faction.PILLAGER, 1, dead.position(), dead);
        }
        // Villages only hold it against you when someone saw it (law and order does that); pillagers just hear.
        else if (dead instanceof IronGolem golem && !golem.isPlayerCreated()) adjust(player, Factions.Faction.PILLAGER, 4);
        else if (GuardTrust.isGuard(dead)) adjust(player, Factions.Faction.PILLAGER, 4);
        else if (dead instanceof Villager) adjust(player, Factions.Faction.PILLAGER, 2);
    }

    /** A raid won (Hero of the Village). */
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getEffectInstance().getEffect().is(MobEffects.HERO_OF_THE_VILLAGE)) crossover(player, -10);
    }

    /** Curing a zombie villager: the curer is whoever the villager now owes (vanilla's cure gossip). */
    public static void onConvert(LivingConversionEvent.Post event) {
        if (!enabled() || !(event.getEntity() instanceof ZombieVillager) || !(event.getOutcome() instanceof Villager villager)) return;
        if (!(villager.level() instanceof ServerLevel level)) return;
        for (ServerPlayer player : level.players()) {
            if (villager.getGossips().getReputation(player.getUUID(), t -> t == GossipType.MAJOR_POSITIVE) > 0) crossover(player, -3);
        }
    }
}

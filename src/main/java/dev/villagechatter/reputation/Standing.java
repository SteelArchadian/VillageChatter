package dev.villagechatter.reputation;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Your standing with one villager or a whole village, as a title ("Familiar Face", "Good Neighbor", "Legend of the
 * Village"...) for the look-at readout. Standing = vanilla gossip reputation (what trade prices use) plus quest
 * goodwill that hasn't paid out yet. A village is the average over the villagers who meet at that bell.
 *
 * Single place to plug in a future reputation module (Village Chatter roadmap phase 3).
 */
public final class Standing {
    private Standing() {}

    /** What the HUD shows: a colored title, and a small line under it. */
    public record View(Component title, int color, Component detail) {}

    private record Tier(int atLeast, String key, int color) {}

    // Thresholds on the gossip scale (MAJOR gossip counts 5 per point, so these move at a sensible pace).
    private static final List<Tier> TIERS = List.of(
            new Tier(100, "legend", 0xFFD27C),
            new Tier(50, "favorite", 0x6FE3E0),
            new Tier(20, "friend", 0x77DD66),
            new Tier(6, "known", 0xB8E6A0),
            new Tier(-5, "neutral", 0xE8E2D4),
            new Tier(-19, "wary", 0xE8A85C),
            new Tier(-49, "distrusted", 0xE06A50),
            new Tier(Integer.MIN_VALUE, "enemy", 0xC23030));
    private static final int STRANGER_COLOR = 0xB0A898, HERO_COLOR = 0xFFD700;

    /** Villagers within this many blocks of an unclaimed bell still count as its village. */
    private static final double BELL_RADIUS = 48.0;

    public static double score(ServerPlayer player, Villager villager) {
        return villager.getPlayerReputation(player) + player.getData(QuestRegistries.GOODWILL).get(villager.getUUID());
    }

    private static boolean knows(ServerPlayer player, Villager villager) {
        return villager.getGossips().getReputation(player.getUUID(), t -> true) != 0
                || player.getData(QuestRegistries.GOODWILL).get(villager.getUUID()) != 0;
    }

    public static View forVillager(ServerPlayer player, Villager villager) {
        double score = score(player, villager);
        Component name = villager.getName();
        if (!knows(player, villager)) {
            return new View(Component.translatable("villagechatter.standing.villager.stranger"), STRANGER_COLOR, name);
        }
        Tier tier = tier(score);
        return new View(Component.translatable("villagechatter.standing.villager." + tier.key()), tier.color(),
                Component.translatable("villagechatter.standing.detail", name, signed(score)));
    }

    /** The pillagers' meter, shown when you look at a pillager or their ominous banner. */
    public static View forPillagers(ServerPlayer player) {
        double p = dev.villagechatter.reputation.faction.FactionBook.pillagerSeen(player);
        var tier = dev.villagechatter.reputation.faction.FactionBook.tier(p);
        if (dev.villagechatter.reputation.faction.FactionBook.disguised(player)) {
            return new View(Component.translatable("villagechatter.standing.pillager.uneasy"), 0xE8A85C,
                    Component.translatable("villagechatter.standing.faction.disguised"));
        }
        int color = switch (tier) {
            case HATED -> 0xC23030;
            case WARY -> 0xE8A85C;
            case TOLERATED -> 0xE8E2D4;
            case ALLY -> 0x77DD66;
        };
        return new View(Component.translatable("villagechatter.standing.pillager." + tier.name().toLowerCase(java.util.Locale.ROOT)), color,
                Component.translatable("villagechatter.standing.faction_detail",
                        Component.translatable("villagechatter.standing.faction.pillager"), dev.villagechatter.reputation.faction.FactionBook.signed(p)));
    }

    /** The piglins' meter, shown when you look at a piglin. */
    public static View forPiglins(ServerPlayer player) {
        double g = dev.villagechatter.reputation.faction.FactionBook.piglin(player);
        String word = dev.villagechatter.reputation.faction.FactionBook.piglinWord(g);
        int color = switch (word) {
            case "hostile" -> 0xC23030;
            case "wary" -> 0xE8A85C;
            case "friendly" -> 0x77DD66;
            case "honored" -> 0xFFD27C;
            default -> 0xE8E2D4;
        };
        return new View(Component.translatable("villagechatter.standing.piglin." + word), color,
                Component.translatable("villagechatter.standing.faction_detail",
                        Component.translatable("villagechatter.standing.faction.piglin"), dev.villagechatter.reputation.faction.FactionBook.signed(g)));
    }

    /** A banner with the ominous (raid captain's) pattern. */
    public static boolean isOminousBanner(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BannerBlockEntity banner)) return false;
        var ominous = net.minecraft.world.entity.raid.Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN));
        return banner.getPatterns().equals(ominous.get(net.minecraft.core.component.DataComponents.BANNER_PATTERNS));
    }

    /** Null when this bell isn't anyone's meeting place and nobody lives near it. */
    @Nullable
    public static View forBell(ServerPlayer player, ServerLevel level, BlockPos bell) {
        if (!level.getBlockState(bell).is(Blocks.BELL)) return null;
        List<Villager> members = villagersOf(level, bell);
        if (members.isEmpty()) return null;
        boolean known = false;
        double total = 0;
        for (Villager v : members) {
            total += score(player, v);
            known |= knows(player, v);
        }
        int count = members.size();
        for (BlockPos b : Villages.bellCluster(level, bell)) {
            double[] o = others(player, level, new AABB(b).inflate(BELL_RADIUS));
            total += o[0];
            count += (int) o[1];
            known |= o[1] > 0 && o[0] != 0;
        }
        double avg = total / count + dev.villagechatter.life.law.VillageStanding.advocacy(level, player, bell);
        Component town = Villages.label(level, Villages.bellCluster(level, bell).get(0));
        Component detail = Component.translatable("villagechatter.standing.village_detail", members.size(), signed(avg), town);
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
            return new View(Component.translatable("villagechatter.standing.village.hero"), HERO_COLOR, detail);
        }
        if (!known) {
            return new View(Component.translatable("villagechatter.standing.village.stranger"), STRANGER_COLOR,
                    Component.translatable("villagechatter.standing.village_size", members.size(), town));
        }
        Tier tier = tier(avg);
        return new View(Component.translatable("villagechatter.standing.village." + tier.key()), tier.color(), detail);
    }

    /**
     * The player's average standing with the village around a position (adult villagers within 48 blocks), on the
     * same scale as {@link #score}; empty if nobody lives there. Cheap enough to call on events, not every tick.
     */
    public static java.util.OptionalDouble villageScore(ServerPlayer player, ServerLevel level, BlockPos pos) {
        List<Villager> around = level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(BELL_RADIUS), v -> !v.isBaby());
        if (around.isEmpty()) return java.util.OptionalDouble.empty();
        double total = 0;
        for (Villager v : around) total += score(player, v);
        double[] o = others(player, level, new AABB(pos).inflate(BELL_RADIUS));
        return java.util.OptionalDouble.of((total + o[0]) / (around.size() + o[1])
                + dev.villagechatter.life.law.VillageStanding.advocacy(level, player, pos));
    }

    /**
     * Everyone else who counts toward a village's opinion of you: its kids (gossip, like adults), its guards
     * ({@link dev.villagechatter.life.GuardTrust#score}) and its golems (one shared opinion, life.law.VillageStanding).
     * @return {sum of standings, how many}
     */
    static double[] others(ServerPlayer player, ServerLevel level, AABB area) {
        double sum = 0;
        int count = 0;
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area,
                net.minecraft.world.entity.LivingEntity::isAlive)) {
            if (e instanceof Villager v && v.isBaby()) sum += score(player, v);
            else if (dev.villagechatter.life.GuardTrust.isGuard(e)) {
                if (!dev.villagechatter.life.law.Companion.speaksForVillage(e)) continue; // hired guards keep their own counsel
                sum += dev.villagechatter.life.GuardTrust.score(player, e);
            }
            else if (e instanceof net.minecraft.world.entity.animal.IronGolem) sum += dev.villagechatter.life.law.VillageStanding.shared(level, player, e);
            else continue;
            count++;
        }
        return new double[] {sum, count};
    }

    /** Everyone in this bell's town: all bells within reach of each other count as one village. */
    static List<Villager> villagersOf(ServerLevel level, BlockPos bell) {
        return Villages.members(level, Villages.bellCluster(level, bell));
    }

    /** Old single-bell lookup, kept for reference: the villagers whose meeting point is this one bell. */
    static List<Villager> villagersOfSingleBell(ServerLevel level, BlockPos bell) {
        GlobalPos here = GlobalPos.of(level.dimension(), bell);
        AABB area = new AABB(bell).inflate(BELL_RADIUS * 2);
        List<Villager> linked = level.getEntitiesOfClass(Villager.class, area, v -> {
            Optional<GlobalPos> meeting = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
            return meeting.isPresent() && meeting.get().equals(here);
        });
        if (!linked.isEmpty()) return linked;
        return level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(BELL_RADIUS), v -> !v.isBaby());
    }

    private static Tier tier(double score) {
        for (Tier t : TIERS) if (score >= t.atLeast()) return t;
        return TIERS.get(TIERS.size() - 1);
    }

    private static String signed(double score) {
        long rounded = Math.round(score);
        return rounded > 0 ? "+" + rounded : Long.toString(rounded);
    }
}

package dev.villagechatter.quests.quest;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;

/**
 * Reputation changes from quests (and golem kindness): vanilla gossip on the villager, which spreads through the
 * village on its own and affects trade prices. When Village Chatter's reputation module lands it reads the same gossip.
 */
public final class Reputation {
    private Reputation() {}

    /** @param amount in vanilla reputation points (MAJOR gossip counts 5 per point, MINOR 1). */
    public static void adjust(ServerPlayer player, Villager villager, int amount, boolean villageWide) {
        int scaled = (int) Math.round(amount * QuestConfig.REPUTATION_MULTIPLIER.get());
        if (scaled == 0) return;
        addGossip(villager, player, scaled);
        if (villager.level() instanceof ServerLevel level) {
            level.sendParticles(scaled > 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
                    villager.getX(), villager.getEyeY() + 0.4, villager.getZ(), scaled > 0 ? 8 : 3, 0.4, 0.3, 0.4, 0.0);
        }
    }

    /** Village-wide goodwill around a spot (e.g. a golem you were kind to): every villager nearby gets {@code amount}. */
    public static void addVillageGoodwill(ServerPlayer player, net.minecraft.world.entity.Entity center, double amount) {
        if (!(center.level() instanceof ServerLevel level) || amount == 0) return;
        double mult = QuestConfig.REPUTATION_MULTIPLIER.get();
        Goodwill goodwill = player.getData(QuestRegistries.GOODWILL);
        for (Villager v : level.getEntitiesOfClass(Villager.class, center.getBoundingBox().inflate(VILLAGE_RADIUS))) {
            credit(player, goodwill, v, amount * mult);
        }
    }

    /** Villagers within this many blocks of the giver count as their village. */
    public static final double VILLAGE_RADIUS = 64.0;

    /**
     * Small, fractional reputation for little favors. The giver gets {@code giverAmount}, every other villager
     * around them {@code villageAmount}. Points collect per villager and are paid out as gossip in whole steps of 2,
     * since vanilla drops gossip entries below 2.
     */
    public static void addGoodwill(ServerPlayer player, Villager giver, double giverAmount, double villageAmount) {
        double mult = QuestConfig.REPUTATION_MULTIPLIER.get();
        Goodwill goodwill = player.getData(QuestRegistries.GOODWILL);
        if (giverAmount != 0) credit(player, goodwill, giver, giverAmount * mult);
        if (villageAmount != 0 && giver.level() instanceof ServerLevel level) {
            for (Villager other : level.getEntitiesOfClass(Villager.class, giver.getBoundingBox().inflate(VILLAGE_RADIUS), v -> v != giver)) {
                credit(player, goodwill, other, villageAmount * mult);
            }
        }
        if (giver.level() instanceof ServerLevel level && giverAmount > 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, giver.getX(), giver.getEyeY() + 0.4, giver.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
        }
    }

    private static void credit(ServerPlayer player, Goodwill goodwill, Villager villager, double amount) {
        double total = goodwill.get(villager.getUUID()) + amount;
        int whole = (int) (total / 2.0) * 2; // keeps the sign; -3.5 -> -2
        if (whole != 0) {
            villager.getGossips().add(player.getUUID(), whole > 0 ? GossipType.MINOR_POSITIVE : GossipType.MINOR_NEGATIVE, Math.abs(whole));
            total -= whole;
        }
        if (Math.abs(total) < 1e-6) goodwill.points.remove(villager.getUUID());
        else goodwill.points.put(villager.getUUID(), total);
    }

    /**
     * Big changes (10+) become lasting MAJOR gossip (worth 5 each), the rest fading MINOR gossip.
     * Vanilla drops any gossip entry below 2, so tiny amounts are rounded up to 2.
     */
    private static void addGossip(Villager villager, ServerPlayer player, int amount) {
        int abs = Math.abs(amount);
        boolean good = amount > 0;
        int major = abs >= 10 ? abs / 5 : 0;
        int minor = abs - major * 5;
        if (major > 0) villager.getGossips().add(player.getUUID(), good ? GossipType.MAJOR_POSITIVE : GossipType.MAJOR_NEGATIVE, major);
        if (minor > 0) villager.getGossips().add(player.getUUID(), good ? GossipType.MINOR_POSITIVE : GossipType.MINOR_NEGATIVE, Math.max(2, minor));
    }
}

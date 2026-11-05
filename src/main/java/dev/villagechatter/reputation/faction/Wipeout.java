package dev.villagechatter.reputation.faction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.reputation.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Wiping out a village yourself (Factions v2, item 4). When you kill a village's last grown-up, and you did a real
 * share of the killing there lately (not zombies picking them off over time), the villages hold it against you even
 * with no one left to tell: a heavy loss on the village meter. Villages nearby lose heart too, a gossip-level morale
 * loss for every villager in them, though nobody there saw it happen.
 */
public final class Wipeout {
    private Wipeout() {}

    private static final long WINDOW = 3 * 24000L;

    /** Your recent kills per village: player|village key -> {count, first kill's game time, grown-ups it had then}. */
    private static final Map<String, long[]> KILLS = new HashMap<>();

    public static void onDeath(LivingDeathEvent event) {
        if (!FactionBook.enabled() || !(event.getEntity() instanceof Villager dead) || dead.isBaby()) return;
        if (!(dead.level() instanceof ServerLevel level)) return;
        ServerPlayer player = dev.villagechatter.life.law.Blame.culprit(event.getSource());
        if (player == null || player.isCreative()) return;
        Optional<GlobalPos> village = dev.villagechatter.life.law.Witnesses.village(level, dead.blockPosition());
        if (village.isEmpty()) return;
        long now = level.getGameTime();
        String key = player.getUUID() + "|" + FactionBook.key(village.get());
        List<BlockPos> bells = Villages.bellCluster(level, village.get().pos());
        long left = Villages.members(level, bells).stream().filter(v -> v != dead && v.isAlive() && !v.isBaby()).count();
        long[] kills = KILLS.get(key);
        if (kills == null || now - kills[1] > WINDOW) kills = new long[] {0, now, left + 1};
        kills[0]++;
        KILLS.put(key, kills);
        if (left > 0) return;
        // the last one: was it you who emptied the place? (at least half the grown-ups it had when you started)
        if (kills[0] * 2 < kills[2]) return;
        KILLS.remove(key);
        wiped(player, level, village.get().pos());
    }

    /** The village at {@code anchor} is gone by your hand. */
    static void wiped(ServerPlayer player, ServerLevel level, BlockPos anchor) {
        FactionBook.adjust(player, Faction.VILLAGE, -ChatterConfig.WIPEOUT_LOSS.get());
        int morale = ChatterConfig.WIPEOUT_MORALE.get();
        if (morale <= 0) return;
        int radius = ChatterConfig.WIPEOUT_RADIUS.get();
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(anchor).inflate(radius), Villager::isAlive)) {
            v.getGossips().add(player.getUUID(), GossipType.MINOR_NEGATIVE, morale);
        }
    }
}

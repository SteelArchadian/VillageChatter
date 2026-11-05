package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.life.law.Blame;
import dev.villagechatter.life.law.Witnesses;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Two bits of town life around the player.
 * Brawls: a fist fight (or a fight with another player) in a village draws a cheering villager. Hitting villagers,
 * guards, golems or village animals is a crime, not a brawl, so those never count.
 * Watch calls: a guard near you calls the hour at dawn, noon, dusk and midnight, or raises the alarm in a raid.
 */
public final class TownCrowd {
    private TownCrowd() {}

    static final int CHEER = 6, DAWN = 4, NOON = 4, DUSK = 4, MIDNIGHT = 4, ALARM = 3;
    private static final String[] MARKS = {"dawn", "noon", "dusk", "midnight"};
    private static final int[] MARK_LINES = {DAWN, NOON, DUSK, MIDNIGHT};
    private static final long CHEER_COOLDOWN = 2400;
    private static final double CHEER_CHANCE = 0.4, CROWD_REACH = 12, WATCH_REACH = 24;

    private static final Map<UUID, Long> CHEERED = new HashMap<>();
    /** The last quarter of the day each player heard called (day * 4 + quarter), so a frozen clock calls once. */
    private static final Map<UUID, Long> CALLED = new HashMap<>();

    // ------------------------------------------------------------------ brawls

    public static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!ChatterConfig.BRAWL_CHEERS.get() || event.getNewDamage() <= 0 || victim.level().isClientSide()) return;
        ServerPlayer player = Blame.culprit(event.getSource());
        if (player == null || !brawl(player, victim)) return;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (CHEERED.getOrDefault(player.getUUID(), Long.MIN_VALUE) > now || level.random.nextDouble() > CHEER_CHANCE) return;
        if (Witnesses.village(level, player.blockPosition()).isEmpty()) return;
        for (Villager v : LivingVillage.villagersNear(level, player, CROWD_REACH, true)) {
            if (!v.hasLineOfSight(player) || AmbientChatter.isChatting(v) || Onlookers.isWatching(v)) continue;
            CHEERED.put(player.getUUID(), now + CHEER_COOLDOWN);
            v.getLookControl().setLookAt(player);
            Component line = Component.translatable("villagechatter.crowd.cheer." + level.random.nextInt(CHEER));
            LivingVillage.say(player, v, line);
            if (victim instanceof ServerPlayer other) LivingVillage.say(other, v, line);
            return;
        }
    }

    /** Another player, or anything that isn't a crime to hit, hit with a bare fist. */
    private static boolean brawl(ServerPlayer player, LivingEntity victim) {
        if (victim instanceof ServerPlayer) return victim != player;
        if (!player.getMainHandItem().isEmpty() || !(victim instanceof Mob)) return false;
        return !(victim instanceof AbstractVillager) && !(victim instanceof IronGolem) && !GuardTrust.isGuard(victim)
                && !(victim instanceof Animal && victim.getTags().contains(Witnesses.VILLAGE_ANIMAL));
    }

    // ------------------------------------------------------------------ watch calls

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0 || !ChatterConfig.WATCH_CALLS.get()) return;
        ServerLevel level = player.serverLevel();
        if (!level.dimensionType().hasFixedTime() && level.getDayTime() % 6000 < 40) {
            long quarter = level.getDayTime() / 6000;
            if (CALLED.getOrDefault(player.getUUID(), -1L) == quarter) return;
            CALLED.put(player.getUUID(), quarter);
            Mob guard = nearestGuard(level, player);
            if (guard == null) return;
            boolean raid = level.getRaidAt(player.blockPosition()) != null;
            int mark = (int) (quarter % 4);
            String key = raid ? "villagechatter.crowd.watch.alarm." + level.random.nextInt(ALARM)
                    : "villagechatter.crowd.watch." + MARKS[mark] + "." + level.random.nextInt(MARK_LINES[mark]);
            LivingVillage.say(player, guard, Component.translatable(key));
        }
    }

    private static Mob nearestGuard(ServerLevel level, ServerPlayer player) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(WATCH_REACH),
                m -> m.isAlive() && GuardTrust.isGuard(m) && m.getTarget() == null)) {
            double d = m.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }
}

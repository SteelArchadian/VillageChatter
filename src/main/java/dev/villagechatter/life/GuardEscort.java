package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.reputation.Standing;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * Skyrim-style "I've got my eye on you": in a village that's wary of you (but not hostile; golems already handle
 * that), a guard (Guard Villagers or any modded guard) shadows you until you leave or your standing recovers.
 * The worse your standing, the closer they stay.
 */
public final class GuardEscort {
    private GuardEscort() {}

    // On the standing scale (reputation.Standing): wary below -5, distrusted at -20, enemy at -50.
    private static final double WARY = -5, DISTRUSTED = -20, ENEMY = -50;
    private static final double GUARD_SEARCH = 48;

    private record Escort(Mob guard, double distance) {}

    private static final Map<UUID, Escort> ESCORTS = new HashMap<>();

    /** Is this guard currently keeping an eye on this player? (Their dialogue changes.) */
    public static boolean isWatching(Entity guard, ServerPlayer player) {
        Escort e = ESCORTS.get(player.getUUID());
        return e != null && e.guard() == guard;
    }

    static void tickPlayer(ServerPlayer player, int now) {
        Escort current = ESCORTS.get(player.getUUID());
        ServerLevel level = player.serverLevel();
        var village = Standing.villageScore(player, level, player.blockPosition());
        boolean wanted = ChatterConfig.GUARD_ESCORT.get() && village.isPresent() && village.getAsDouble() < WARY
                && !player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) && !player.isCreative() && !player.isSpectator();
        if (!wanted) {
            if (current != null) ESCORTS.remove(player.getUUID());
            return;
        }
        double score = village.getAsDouble();
        double distance = score < ENEMY ? 2.5 : score < DISTRUSTED ? 4 : 7;
        if (current == null || !current.guard().isAlive() || current.guard().level() != level) {
            Mob guard = findGuard(level, player);
            if (guard == null) return;
            current = new Escort(guard, distance);
            ESCORTS.put(player.getUUID(), current);
            LivingVillage.say(player, guard, Component.translatable("villagechatter.life.escort." + level.random.nextInt(3)));
        } else if (current.distance() != distance) {
            current = new Escort(current.guard(), distance);
            ESCORTS.put(player.getUUID(), current);
        }
        Mob guard = current.guard();
        if (guard.getTarget() != null) return; // busy fighting something real
        guard.getLookControl().setLookAt(player);
        if (guard.distanceTo(player) > current.distance()) guard.getNavigation().moveTo(player, 1.0);
        else guard.getNavigation().stop();
    }

    /** The nearest guard: any non-villager mob with "guard" in its id (Guard Villagers' guards and the like). */
    private static Mob findGuard(ServerLevel level, ServerPlayer player) {
        Mob best = null;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(GUARD_SEARCH),
                m -> m.isAlive() && GuardTrust.isGuard(m))) {
            boolean taken = ESCORTS.values().stream().anyMatch(e -> e.guard() == m);
            if (taken) continue;
            if (best == null || m.distanceToSqr(player) < best.distanceToSqr(player)) best = m;
        }
        return best;
    }
}

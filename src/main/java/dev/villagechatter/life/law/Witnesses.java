package dev.villagechatter.life.law;

import java.util.List;
import java.util.Optional;

import dev.villagechatter.life.GuardTrust;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * Who saw it. Sight is everything: a witness has to be awake, within {@link #RANGE}, with a clear line of sight and
 * the culprit inside their view cone (or close enough to hear it). Nobody saw it, nobody knows.
 */
public final class Witnesses {
    private Witnesses() {}

    public static final double RANGE = 24;
    /** Closer than this they notice even behind them. */
    private static final double HEARING = 2;
    /** cos(45 degrees): a simple 90 degree view cone. */
    private static final double CONE = 0.707;
    public static final String VILLAGE_ANIMAL = "villagechatter:village_animal";

    /** Everyone who saw {@code culprit} just now, nearest first; never the culprit. */
    public static List<LivingEntity> of(ServerLevel level, Entity culprit) {
        return level.getEntitiesOfClass(LivingEntity.class, culprit.getBoundingBox().inflate(RANGE),
                        e -> e != culprit && canWitness(e) && sees(e, culprit))
                .stream().sorted((a, b) -> Double.compare(a.distanceToSqr(culprit), b.distanceToSqr(culprit))).toList();
    }

    /** Villagers (kids too), guards, golems, and village horses (who can't talk, but tell a guard later). */
    public static boolean canWitness(LivingEntity e) {
        if (!e.isAlive() || e.isSleeping()) return false;
        if (e instanceof Villager || e instanceof IronGolem || GuardTrust.isGuard(e)) return true;
        return e instanceof AbstractHorse horse && !horse.isTamed() && horse.getTags().contains(VILLAGE_ANIMAL);
    }

    public static boolean sees(LivingEntity witness, Entity target) {
        double dist = witness.distanceTo(target);
        if (dist > RANGE || !witness.hasLineOfSight(target)) return false;
        if (dist <= HEARING) return true;
        Vec3 look = witness.getViewVector(1.0F);
        Vec3 to = target.getEyePosition().subtract(witness.getEyePosition()).normalize();
        return look.dot(to) >= CONE;
    }

    /** The village a spot belongs to (its town's anchor bell), or empty when no bell is in range. */
    public static Optional<GlobalPos> village(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), pos, 64, PoiManager.Occupancy.ANY)
                .map(p -> GlobalPos.of(level.dimension(), dev.villagechatter.reputation.Villages.bellCluster(level, p).get(0)));
    }
}

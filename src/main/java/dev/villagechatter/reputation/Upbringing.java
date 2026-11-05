package dev.villagechatter.reputation;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

import javax.annotation.Nullable;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * What a kid thinks of you carries into adulthood: growing up is the same entity with the same UUID, so its gossip
 * and goodwill (both keyed on the villager) simply stay. A newborn doesn't start blank either: it takes on its
 * parents' average opinion of each player, or its village's when no parents are around.
 */
public final class Upbringing {
    private Upbringing() {}

    /** Breeding parents stand right next to where the baby appears. */
    private static final double PARENT_RANGE = 3.0;

    /** Villager breeding fires no BabyEntitySpawnEvent, so newborns are caught as they first join the world. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !ChatterConfig.NEWBORN_INHERITS.get()) return;
        if (!(event.getEntity() instanceof Villager child) || !child.isBaby() || !(event.getLevel() instanceof ServerLevel level)) return;
        List<Villager> parents = level.getEntitiesOfClass(Villager.class, new AABB(child.blockPosition()).inflate(PARENT_RANGE),
                v -> v != child && !v.isBaby() && v.isAlive());
        parents.sort(Comparator.comparingDouble(v -> v.distanceToSqr(child)));
        Villager a = parents.size() > 0 ? parents.get(0) : null;
        Villager b = parents.size() > 1 ? parents.get(1) : null;
        for (ServerPlayer player : level.players()) inherit(player, level, child, a, b);
    }

    /** Sets the child's standing with this player to its parents' average (or the village's). */
    public static void inherit(ServerPlayer player, ServerLevel level, Villager child, @Nullable Villager a, @Nullable Villager b) {
        double start;
        if (a != null || b != null) {
            double sum = 0;
            int n = 0;
            if (a != null) { sum += Standing.score(player, a); n++; }
            if (b != null) { sum += Standing.score(player, b); n++; }
            start = sum / n;
        } else {
            OptionalDouble village = Standing.villageScore(player, level, child.blockPosition());
            if (village.isEmpty()) return;
            start = village.getAsDouble();
        }
        double change = start - Standing.score(player, child);
        if (Math.abs(change) < 0.01) return;
        player.getData(QuestRegistries.GOODWILL).points.merge(child.getUUID(), change, Double::sum);
    }
}

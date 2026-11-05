package dev.villagechatter.quests.quest;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Villagers that follow a player (rescued buddies, escorts, couriers' travel companions...). Villagers run on the
 * brain system, not goals, so "following" means refreshing their walk target toward the leader; call
 * {@link #follow} about once a second.
 */
public final class Escort {
    private Escort() {}

    /** What a villager follows by default: you hold it up, they come along. */
    public static final Item LURE = Items.EMERALD_BLOCK;
    public static final double DEFAULT_RANGE = 24.0;
    private static final float SPEED = 0.75F;

    public static boolean holdingLure(ServerPlayer player) {
        return player.getMainHandItem().is(LURE) || player.getOffhandItem().is(LURE);
    }

    /**
     * Walks the villager toward the leader if they're holding the lure and within range.
     * @return true if the villager is following this second.
     */
    public static boolean follow(ServerPlayer leader, Villager villager, double range) {
        if (!holdingLure(leader) || villager.level() != leader.level() || villager.distanceTo(leader) > range) return false;
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(leader, SPEED, 2));
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(leader, true));
        return true;
    }

    /** Stop following (arrived, released). */
    public static void stop(Villager villager) {
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
}

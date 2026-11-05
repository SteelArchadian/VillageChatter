package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Building in a village draws the occasional curious onlooker: now and then one villager wanders over, stops a few
 * blocks away (never underfoot), watches for a bit with an "ooh" or a "what's that?", then goes back to their day.
 */
public final class Onlookers {
    private Onlookers() {}

    private static final double CHANCE = 0.10;
    private static final int PLAYER_COOLDOWN = 20 * 60;
    private static final double KEEP_AWAY = 3.5, PICK_RANGE = 24;
    private static final int WALK_TICKS = 20 * 8, WATCH_TICKS = 20 * 6;

    private static final class Watch {
        final Villager villager;
        final ServerPlayer player;
        final int started;
        int watchingSince = -1;

        Watch(Villager villager, ServerPlayer player, int started) {
            this.villager = villager;
            this.player = player;
            this.started = started;
        }
    }

    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_NEXT = new HashMap<>();

    public static boolean isWatching(Villager v) {
        return WATCHES.containsKey(v.getUUID());
    }

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
        Kindness.onBuild(player, event.getPlacedBlock(), event.getPos());
        if (!ChatterConfig.VILLAGE_REACTIONS.get()) return;
        int now = LivingVillage.tick();
        if (PLAYER_NEXT.getOrDefault(player.getUUID(), 0) > now || !LivingVillage.inVillage(level, player)) return;
        PLAYER_NEXT.put(player.getUUID(), now + PLAYER_COOLDOWN / 4); // don't re-roll every block
        if (level.random.nextDouble() >= CHANCE) return;
        Villager pick = null;
        for (Villager v : LivingVillage.villagersNear(level, player, PICK_RANGE, true)) {
            if (!LivingVillage.idle(v) || v.distanceTo(player) < KEEP_AWAY) continue;
            if (pick == null || v.distanceToSqr(player) < pick.distanceToSqr(player)) pick = v;
        }
        if (pick == null) return;
        PLAYER_NEXT.put(player.getUUID(), now + PLAYER_COOLDOWN);
        WATCHES.put(pick.getUUID(), new Watch(pick, player, now));
    }

    static void tick(int now) {
        if (WATCHES.isEmpty()) return;
        WATCHES.values().removeIf(w -> {
            Villager v = w.villager;
            if (!v.isAlive() || !w.player.isAlive() || v.level() != w.player.level() || v.isSleeping()) return true;
            if (w.watchingSince < 0) {
                // Walk to a spot a few blocks from the player, on our side of them.
                Vec3 toUs = v.position().subtract(w.player.position()).normalize();
                BlockPos spot = BlockPos.containing(w.player.position().add(toUs.scale(KEEP_AWAY)));
                v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.5F, 1));
                if (v.distanceTo(w.player) <= KEEP_AWAY + 1.5) {
                    w.watchingSince = now;
                    LivingVillage.babble(v, 2 + v.getRandom().nextInt(3), 4, v.getRandom().nextBoolean() ? 0.14F : 0.06F, 0.6F);
                } else if (now - w.started > WALK_TICKS) {
                    return true; // couldn't get there; never mind
                }
            } else {
                v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                v.getNavigation().stop();
                v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(w.player, true));
                if (now - w.watchingSince > WATCH_TICKS) {
                    if (v.getRandom().nextFloat() < 0.5F) LivingVillage.babble(v, 2, 0, -0.05F, 0.5F); // "huh, alright"
                    return true;
                }
            }
            return false;
        });
    }
}

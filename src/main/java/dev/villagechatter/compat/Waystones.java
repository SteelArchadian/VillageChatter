package dev.villagechatter.compat;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;

/**
 * Soft compat with Waystones: a named waystone in a village names the village. Read through reflection
 * (WaystoneBlockEntityBase#getWaystone, Waystone#hasName/#getName), so there's no build dependency and
 * nothing happens without the mod.
 */
public final class Waystones {
    private Waystones() {}

    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) loaded = ModList.get().isLoaded("waystones");
        return loaded;
    }

    /** The name of the nearest named waystone within radius (loaded chunks only), or null. */
    @Nullable
    public static Component nameNear(ServerLevel level, BlockPos center, int radius) {
        if (!isLoaded()) return null;
        Component best = null;
        double bestSq = (double) radius * radius;
        int r = (radius >> 4) + 1;
        for (int cx = (center.getX() >> 4) - r; cx <= (center.getX() >> 4) + r; cx++) {
            for (int cz = (center.getZ() >> 4) - r; cz <= (center.getZ() >> 4) + r; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!isWaystone(be)) continue;
                    double d = be.getBlockPos().distSqr(center);
                    if (d > bestSq) continue;
                    Component name = nameOf(be);
                    if (name != null) {
                        best = name;
                        bestSq = d;
                    }
                }
            }
        }
        return best;
    }

    public static boolean isWaystone(BlockEntity be) {
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id != null && id.getNamespace().equals("waystones") && id.getPath().contains("waystone");
    }

    @Nullable
    private static Component nameOf(BlockEntity be) {
        try {
            Method getWaystone = be.getClass().getMethod("getWaystone");
            Object waystone = getWaystone.invoke(be);
            if (waystone == null) return null;
            Method hasName = waystone.getClass().getMethod("hasName");
            if (!(boolean) hasName.invoke(waystone)) return null;
            Object name = waystone.getClass().getMethod("getName").invoke(waystone);
            return name instanceof Component c && !c.getString().isBlank() ? c : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

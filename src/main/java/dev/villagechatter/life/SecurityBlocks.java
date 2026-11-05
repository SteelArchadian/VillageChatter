package dev.villagechatter.life;

import dev.villagechatter.ChatterConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Is this block village security (walls, fences, gates, doors, traps, turrets...)? Read from the block's id, so a
 * modded {@code somemod:iron_portcullis} counts without explicit support. Shared with the guards' security quest.
 */
public final class SecurityBlocks {
    private SecurityBlocks() {}

    public static boolean isDefensive(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        for (String no : ChatterConfig.SECURITY_EXCLUDED.get()) if (id.contains(no)) return false;
        if (state.is(BlockTags.WALLS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) return true;
        for (String word : ChatterConfig.SECURITY_WORDS.get()) if (id.contains(word)) return true;
        return false;
    }
}

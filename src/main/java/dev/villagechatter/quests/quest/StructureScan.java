package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Fast "where could this structure be" from the world seed: walks the structure's placement grid and keeps the
 * candidate chunks whose biome fits. No chunks are loaded or generated, so it costs microseconds per candidate,
 * where vanilla's locate (which checks every candidate for real) can take most of a second.
 *
 * It's a very good guess, not a guarantee: terrain can still stop a structure from generating. Rumors use it as
 * is ("or so they say"); quest targets confirm the best candidates for real.
 */
public final class StructureScan {
    private StructureScan() {}

    /** Biomes are 3D: check a few heights (sea level up into the hills) so mountain villages aren't missed. */
    private static boolean biomeFits(Holder<Structure> structure, BiomeSource biomes, Climate.Sampler sampler, int bx, int bz, int seaQuart) {
        for (int up = 0; up <= 12; up += 6) {
            if (structure.value().biomes().contains(biomes.getNoiseBiome(QuartPos.fromBlock(bx), seaQuart + up, QuartPos.fromBlock(bz), sampler))) {
                return true;
            }
        }
        return false;
    }

    /** Candidate chunks for this structure within [minBlocks, maxBlocks] of center, nearest first. */
    public static List<ChunkPos> candidates(ServerLevel level, Holder<Structure> structure, BlockPos center, int minBlocks, int maxBlocks) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        long seed = state.getLevelSeed();
        int seaQuart = QuartPos.fromBlock(level.getSeaLevel());
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        int maxChunks = (maxBlocks >> 4) + 1;
        double minSq = (double) minBlocks * minBlocks, maxSq = (double) maxBlocks * maxBlocks;

        List<ChunkPos> out = new ArrayList<>();
        for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
            if (!(placement instanceof RandomSpreadStructurePlacement spread)) continue;
            int spacing = spread.spacing();
            int fromX = Math.floorDiv(cx - maxChunks, spacing), toX = Math.floorDiv(cx + maxChunks, spacing);
            int fromZ = Math.floorDiv(cz - maxChunks, spacing), toZ = Math.floorDiv(cz + maxChunks, spacing);
            for (int gx = fromX; gx <= toX; gx++) {
                for (int gz = fromZ; gz <= toZ; gz++) {
                    ChunkPos chunk = spread.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
                    int bx = chunk.getMiddleBlockX(), bz = chunk.getMiddleBlockZ();
                    double dx = bx - center.getX(), dz = bz - center.getZ();
                    double distSq = dx * dx + dz * dz;
                    if (distSq < minSq || distSq > maxSq) continue;
                    if (!spread.isStructureChunk(state, chunk.x, chunk.z)) continue;
                    if (!biomeFits(structure, biomes, sampler, bx, bz, seaQuart)) continue;
                    out.add(chunk);
                }
            }
        }
        out.sort(Comparator.comparingDouble(c -> {
            double dx = c.getMiddleBlockX() - center.getX(), dz = c.getMiddleBlockZ() - center.getZ();
            return dx * dx + dz * dz;
        }));
        return out;
    }
}

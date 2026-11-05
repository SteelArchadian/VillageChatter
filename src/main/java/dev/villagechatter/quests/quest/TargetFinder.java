package dev.villagechatter.quests.quest;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Picks where a quest's target is, when the quest is accepted. */
public final class TargetFinder {
    private TargetFinder() {}

    public record Target(BlockPos pos, String kind) {}

    /** Columns tried per chunk when looking for a cave. */
    private static final int CAVE_COLUMNS_PER_CHUNK = 24;

    /** Tries each location kind in order; falls back to a surface spot so a quest is never left without a target. */
    public static Target find(ServerLevel level, BlockPos origin, QuestDefinition.Location location, RandomSource random) {
        int min = location.clampedMin();
        int max = location.clampedMax();
        for (String kind : location.kinds()) {
            Target found = switch (kind) {
                case "structure" -> location.structureTag() == null ? null
                        : findStructure(level, origin, location.structureTag(), min, location.structureMax(), random);
                case "cave" -> findCave(level, origin, min, max, random);
                case "forest" -> findForest(level, origin, min, max, random);
                case "surface" -> findSurface(level, origin, min, max, random);
                default -> {
                    QuestsModule.LOGGER.warn("Unknown location kind '{}'", kind);
                    yield null;
                }
            };
            if (found != null) return found;
        }
        return findSurface(level, origin, min, max, random);
    }

    // ------------------------------------------------------------------ structures

    @Nullable
    private static Target findStructure(ServerLevel level, BlockPos origin, ResourceLocation tagId, int min, int max,
                                        RandomSource random) {
        TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, tagId);
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        // Seed math finds every plausible spot in the ring at once (StructureScan); then a few of them,
        // picked at random so it isn't always the nearest, are confirmed for real.
        List<ChunkPos> candidates = new java.util.ArrayList<>();
        registry.getTag(tag).ifPresent(set -> set.forEach(h -> candidates.addAll(StructureScan.candidates(level, h, origin, min, max))));
        java.util.Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
        int checks = 0;
        for (ChunkPos found : candidates) {
            if (checks++ >= 8) break;
            QuestsModule.LOGGER.debug("Structure candidate {} at {}", tagId, found);
            ChunkAccess chunk = level.getChunk(found.x, found.z, ChunkStatus.STRUCTURE_STARTS);
            for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                StructureStart start = entry.getValue();
                if (!start.isValid() || !registry.wrapAsHolder(entry.getKey()).is(tag)) continue;
                List<StructurePiece> pieces = start.getPieces();
                if (pieces.isEmpty()) continue;
                // Try a few pieces; take one inside the distance ring.
                for (int i = 0; i < 8; i++) {
                    BlockPos center = pieces.get(random.nextInt(pieces.size())).getBoundingBox().getCenter();
                    double dist = Math.sqrt(center.distSqr(new BlockPos(origin.getX(), center.getY(), origin.getZ())));
                    if (dist >= min && dist <= max) {
                        ResourceLocation id = registry.getKey(entry.getKey());
                        return new Target(center, id == null ? "structure" : id.toString());
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ caves

    @Nullable
    private static Target findCave(ServerLevel level, BlockPos origin, int min, int max, RandomSource random) {
        int chunks = QuestConfig.CAVE_SEARCH_CHUNKS.get();
        for (int c = 0; c < chunks; c++) {
            BlockPos probe = ringPoint(origin, Mth.nextInt(random, min, max), random);
            // Loads (and if needed generates) this one chunk.
            level.getChunk(probe.getX() >> 4, probe.getZ() >> 4);
            int baseX = probe.getX() & ~15, baseZ = probe.getZ() & ~15;
            for (int col = 0; col < CAVE_COLUMNS_PER_CHUNK; col++) {
                int x = baseX + random.nextInt(16), z = baseZ + random.nextInt(16);
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surface - 12, z);
                for (; pos.getY() > level.getMinBuildHeight() + 6; pos.move(Direction.DOWN)) {
                    if (isCaveSpot(level, pos)) return new Target(pos.immutable(), "cave");
                }
            }
        }
        return null;
    }

    /** Standing room in an actual cave: open floor with some air around it, no fluids. */
    private static boolean isCaveSpot(ServerLevel level, BlockPos pos) {
        if (!isStandable(level, pos)) return false;
        int open = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isAirLike(level, pos.relative(d)) && isAirLike(level, pos.relative(d).above())) open++;
        }
        return open >= 3 && !level.canSeeSky(pos);
    }

    // ------------------------------------------------------------------ forests

    /** A surface spot in a forest biome (#minecraft:is_forest), trying a few points around the ring. */
    @Nullable
    private static Target findForest(ServerLevel level, BlockPos origin, int min, int max, RandomSource random) {
        int tries = QuestConfig.CAVE_SEARCH_CHUNKS.get() * 2;
        for (int t = 0; t < tries; t++) {
            BlockPos probe = ringPoint(origin, Mth.nextInt(random, min, max), random);
            // Biomes come from the seed; only generate the chunk once the biome is right.
            int quartY = net.minecraft.core.QuartPos.fromBlock(level.getSeaLevel() + 8);
            var biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                    net.minecraft.core.QuartPos.fromBlock(probe.getX()), quartY, net.minecraft.core.QuartPos.fromBlock(probe.getZ()),
                    level.getChunkSource().randomState().sampler());
            if (!biome.is(net.minecraft.tags.BiomeTags.IS_FOREST)) continue;
            level.getChunk(probe.getX() >> 4, probe.getZ() >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ());
            return new Target(new BlockPos(probe.getX(), y, probe.getZ()), "forest");
        }
        return null;
    }

    // ------------------------------------------------------------------ surface

    private static Target findSurface(ServerLevel level, BlockPos origin, int min, int max, RandomSource random) {
        BlockPos probe = ringPoint(origin, Mth.nextInt(random, min, max), random);
        level.getChunk(probe.getX() >> 4, probe.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ());
        return new Target(new BlockPos(probe.getX(), y, probe.getZ()), "surface");
    }

    // ------------------------------------------------------------------ helpers

    private static BlockPos ringPoint(BlockPos origin, int distance, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2;
        return origin.offset((int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance));
    }

    static boolean isAirLike(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    static boolean isStandable(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return isAirLike(level, pos) && isAirLike(level, pos.above())
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** Nearest standable spot within {@code radius} of {@code center}, scanning outward; null if none is loaded. */
    @Nullable
    public static BlockPos findSpawnSpot(ServerLevel level, BlockPos center, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        BlockPos p = center.offset(dx, dy, dz);
                        if (level.isLoaded(p) && isStandable(level, p)) return p;
                    }
                }
            }
        }
        return null;
    }
}

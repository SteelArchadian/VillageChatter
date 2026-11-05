package dev.villagechatter.reputation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import dev.villagechatter.compat.Waystones;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

/**
 * What counts as one village. Bells within {@link #BELL_LINK} blocks of each other (chained) are one town, so a
 * village with two bells is still one village. A village is identified by its "anchor": the lowest bell position in
 * that cluster, which stays the same as long as the bells do. Villages can have names (from a Waystones waystone in
 * town, when that mod is installed), remembered in {@link Names} so they still show when you're far away.
 */
public final class Villages {
    private Villages() {}

    public static final int BELL_LINK = 64;
    private static final int MAX_BELLS = 12;
    /** How far from a village's anchor its name still applies. */
    private static final int NAME_RANGE = 96;

    /** All bells chained to this one (itself included), lowest first. */
    public static List<BlockPos> bellCluster(ServerLevel level, BlockPos bell) {
        Set<BlockPos> found = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        found.add(bell.immutable());
        queue.add(bell.immutable());
        while (!queue.isEmpty() && found.size() < MAX_BELLS) {
            BlockPos at = queue.poll();
            level.getPoiManager().findAll(h -> h.is(PoiTypes.MEETING), p -> true, at, BELL_LINK, PoiManager.Occupancy.ANY)
                    .forEach(p -> {
                        if (found.size() < MAX_BELLS && found.add(p.immutable())) queue.add(p.immutable());
                    });
        }
        List<BlockPos> out = new ArrayList<>(found);
        out.sort(Comparator.comparingLong(BlockPos::asLong));
        return out;
    }

    /** The villagers who meet at any bell of this town; if none do, everyone living near the bells. */
    public static List<Villager> members(ServerLevel level, List<BlockPos> bells) {
        Set<GlobalPos> meeting = new java.util.HashSet<>();
        AABB area = null;
        for (BlockPos b : bells) {
            meeting.add(GlobalPos.of(level.dimension(), b));
            AABB around = new AABB(b).inflate(BELL_LINK + 32);
            area = area == null ? around : area.minmax(around);
        }
        if (area == null) return List.of();
        List<Villager> linked = level.getEntitiesOfClass(Villager.class, area, v -> {
            Optional<GlobalPos> m = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
            return m.isPresent() && meeting.contains(m.get());
        });
        if (!linked.isEmpty()) return linked;
        List<Villager> near = new ArrayList<>();
        for (BlockPos b : bells) {
            for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(b).inflate(48), v -> !v.isBaby())) {
                if (!near.contains(v)) near.add(v);
            }
        }
        return near;
    }

    /** Where this villager's village is: the anchor bell of their meeting point's town, or where they stand. */
    public static BlockPos center(ServerLevel level, Villager villager) {
        Optional<GlobalPos> meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
        if (meeting.isPresent() && meeting.get().dimension() == level.dimension() && level.isLoaded(meeting.get().pos())) {
            return bellCluster(level, meeting.get().pos()).get(0);
        }
        return villager.blockPosition();
    }

    /** Clicking a waystone in a village teaches us the village's name (for the journal and the standing readout). */
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Waystones.isLoaded()) return;
        var be = level.getBlockEntity(event.getPos());
        if (be == null || !Waystones.isWaystone(be)) {
            // Waystones are two blocks tall: the click may land on the top half.
            be = level.getBlockEntity(event.getPos().below());
            if (be == null || !Waystones.isWaystone(be)) return;
        }
        BlockPos at = be.getBlockPos();
        level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), at, 96, PoiManager.Occupancy.ANY)
                .ifPresent(bell -> learnName(level, bellCluster(level, bell).get(0)));
    }

    /** Looks for a named waystone in town (if Waystones is installed) and remembers the name. */
    public static void learnName(ServerLevel level, BlockPos anchor) {
        Component name = Waystones.nameNear(level, anchor, 64);
        if (name != null) Names.get(level).put(anchor, name.getString());
    }

    /** The village's name, if one is known near this spot. */
    @Nullable
    public static String knownName(ServerLevel level, BlockPos pos) {
        return Names.get(level).near(pos, NAME_RANGE);
    }

    /** "Riverbend" (waystone name) or "the village near X, Z". */
    public static Component label(@Nullable ServerLevel level, BlockPos anchor) {
        String name = level == null ? null : knownName(level, anchor);
        return name != null ? Component.literal(name)
                : Component.translatable("villagechatter.village.near", anchor.getX(), anchor.getZ());
    }

    // ------------------------------------------------------------------ remembered names (per dimension)

    public static final class Names extends SavedData {
        private static final String ID = "villagechatter_village_names";
        private static final Codec<Map<Long, String>> CODEC = Codec.unboundedMap(Codec.STRING.xmap(Long::parseLong, String::valueOf), Codec.STRING);
        private final Map<Long, String> names = new HashMap<>();

        static Names get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Names::new, Names::load, null), ID);
        }

        private static Names load(CompoundTag tag, HolderLookup.Provider registries) {
            Names n = new Names();
            CODEC.parse(NbtOps.INSTANCE, tag.get("names")).result().ifPresent(n.names::putAll);
            return n;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CODEC.encodeStart(NbtOps.INSTANCE, names).result().ifPresent(t -> tag.put("names", t));
            return tag;
        }

        void put(BlockPos anchor, String name) {
            if (!name.equals(names.put(anchor.asLong(), name))) setDirty();
        }

        @Nullable
        String near(BlockPos pos, int range) {
            String best = null;
            double bestSq = (double) range * range;
            for (Map.Entry<Long, String> e : names.entrySet()) {
                BlockPos p = BlockPos.of(e.getKey());
                double d = p.distSqr(new BlockPos(pos.getX(), p.getY(), pos.getZ()));
                if (d <= bestSq) {
                    bestSq = d;
                    best = e.getValue();
                }
            }
            return best;
        }
    }
}

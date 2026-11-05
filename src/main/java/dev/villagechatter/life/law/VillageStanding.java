package dev.villagechatter.life.law;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.life.GuardTrust;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * A village's golems are of one mind, and so are its guards: anger one and you anger them all. This keeps that shared
 * standing, per village (its anchor bell) and per player, saved per dimension. A villager's own standing stays in
 * vanilla gossip. The village as a whole is the average of all its members (reputation.Standing), never stored.
 */
public final class VillageStanding extends SavedData {
    /**
     * GOLEMS and GUARDS: the shared opinion. ADVOCACY: a head start the village gave you because a hired guard
     * vouched for you on your first visit. VISITED: 1 once you've been there.
     */
    public enum Group { GOLEMS, GUARDS, ADVOCACY, VISITED }

    private final Map<Long, Map<UUID, double[]>> villages = new HashMap<>();

    public static VillageStanding get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(VillageStanding::new, VillageStanding::load, null),
                "villagechatter_village_standing");
    }

    public double get(BlockPos village, UUID player, Group group) {
        Map<UUID, double[]> players = villages.get(village.asLong());
        double[] v = players == null ? null : players.get(player);
        return v == null || v.length <= group.ordinal() ? 0 : v[group.ordinal()];
    }

    public void add(BlockPos village, UUID player, Group group, double amount) {
        if (amount == 0) return;
        Map<UUID, double[]> players = villages.computeIfAbsent(village.asLong(), k -> new HashMap<>());
        double[] old = players.get(player);
        if (old != null && old.length < Group.values().length) players.put(player, java.util.Arrays.copyOf(old, Group.values().length));
        double[] v = villages.computeIfAbsent(village.asLong(), k -> new HashMap<>()).computeIfAbsent(player, k -> new double[Group.values().length]);
        v[group.ordinal()] = Math.max(-200, Math.min(200, v[group.ordinal()] + amount));
        setDirty();
    }

    /** Shared standing for one member's group, or 0 for anyone who isn't a golem or guard. */
    public static double shared(ServerLevel level, ServerPlayer player, LivingEntity member) {
        Group group = member instanceof IronGolem ? Group.GOLEMS : GuardTrust.isGuard(member) ? Group.GUARDS : null;
        if (group == null) return 0;
        return Witnesses.village(level, member.blockPosition()).map(v -> get(level).get(v.pos(), player.getUUID(), group)).orElse(0.0);
    }

    /** The village-level head start a hired guard won you here (added to the village's average, not to anyone in it). */
    public static double advocacy(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return Witnesses.village(level, pos).map(v -> get(level).get(v.pos(), player.getUUID(), Group.ADVOCACY)).orElse(0.0);
    }

    private static VillageStanding load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageStanding data = new VillageStanding();
        for (Tag t : tag.getList("villages", Tag.TAG_COMPOUND)) {
            CompoundTag village = (CompoundTag) t;
            Map<UUID, double[]> players = new HashMap<>();
            for (Tag p : village.getList("players", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) p;
                players.put(entry.getUUID("id"), new double[] {entry.getDouble("golems"), entry.getDouble("guards"),
                        entry.getDouble("advocacy"), entry.getDouble("visited")});
            }
            data.villages.put(village.getLong("pos"), players);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        villages.forEach((pos, players) -> {
            CompoundTag village = new CompoundTag();
            village.putLong("pos", pos);
            ListTag entries = new ListTag();
            players.forEach((id, v) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("id", id);
                entry.putDouble("golems", v[0]);
                entry.putDouble("guards", v[1]);
                if (v.length > 3) {
                    entry.putDouble("advocacy", v[2]);
                    entry.putDouble("visited", v[3]);
                }
                entries.add(entry);
            });
            village.put("players", entries);
            list.add(village);
        });
        tag.put("villages", list);
        return tag;
    }
}

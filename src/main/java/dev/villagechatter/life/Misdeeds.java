package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Small misdeeds and how villagers take them.
 *  - Breaking a villager's bed in front of them gets a "Wait, what are you doing?!" (no reputation hit: accidents
 *    happen).
 *  - Taking items from a village's chests and barrels (ones you didn't place) quietly costs a tiny bit of village
 *    goodwill per item. They let it slide, but it adds up if you make a habit of it.
 */
public final class Misdeeds {
    private Misdeeds() {}

    // ------------------------------------------------------------------ beds

    private static final Map<UUID, Integer> BED_NEXT = new HashMap<>();

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        PlacedContainers.get(level).forget(event.getPos());
        if (!(state.getBlock() instanceof BedBlock) || !ChatterConfig.VILLAGE_REACTIONS.get()) return;
        int now = LivingVillage.tick();
        if (BED_NEXT.getOrDefault(player.getUUID(), 0) > now) return;
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? event.getPos()
                : event.getPos().relative(state.getValue(BedBlock.FACING));
        GlobalPos home = GlobalPos.of(level.dimension(), head);
        for (Villager v : LivingVillage.villagersNear(level, player, 24, false)) {
            if (v.isSleeping() && v.getSleepingPos().filter(p -> p.equals(head) || p.equals(event.getPos())).isPresent()) {
                // Pulling the bed out from under someone: that's a crime (life.law), if anyone sees it.
                dev.villagechatter.life.law.Law.commit(player, dev.villagechatter.life.law.CrimeKind.BED, v, null, null);
            }
            Optional<GlobalPos> theirs = v.getBrain().getMemory(MemoryModuleType.HOME);
            if (theirs.isEmpty() || !theirs.get().equals(home) || !v.hasLineOfSight(player)) continue;
            BED_NEXT.put(player.getUUID(), now + 200);
            v.getLookControl().setLookAt(player);
            LivingVillage.babble(v, 5, 0, 0.2F, 0.9F); // agitated
            LivingVillage.say(player, v, Component.translatable("villagechatter.life.bed." + level.random.nextInt(3)));
            return;
        }
    }

    // ------------------------------------------------------------------ chests

    private static final double GOODWILL_PER_ITEM = 0.1, MAX_PER_VISIT = 2.0;

    /** Open container per player: its position and what was in it when opened. */
    private record Opened(BlockPos pos, int items, java.util.List<net.minecraft.world.item.ItemStack> contents) {}

    private static final Map<UUID, BlockPos> LAST_CLICKED = new HashMap<>();
    private static final Map<UUID, Opened> OPEN = new HashMap<>();

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockEntity be = level.getBlockEntity(event.getPos());
        if (be instanceof Container) PlacedContainers.get(level).remember(event.getPos());
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) LAST_CLICKED.put(player.getUUID(), event.getPos().immutable());
    }

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ChatterConfig.CHEST_REPUTATION.get()) return;
        BlockPos pos = LAST_CLICKED.remove(player.getUUID());
        if (pos == null || !(player.level().getBlockEntity(pos) instanceof Container)) return;
        if (PlacedContainers.get(player.serverLevel()).placedByPlayer(pos) || !LivingVillage.inVillage(player.serverLevel(), player)) return;
        OPEN.put(player.getUUID(), new Opened(pos, count(event.getContainer()), contents(event.getContainer())));
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Opened opened = OPEN.remove(player.getUUID());
        if (opened == null) return;
        int taken = opened.items() - count(event.getContainer());
        if (taken <= 0) return;
        Villager owner = owner(player.serverLevel(), opened.pos());
        if (owner != null) {
            double standing = dev.villagechatter.reputation.Standing.score(player, owner);
            if (standing >= ChatterConfig.HOUSE_LOOT_FREE.get()) return; // a friend: help yourself
            if (standing >= ChatterConfig.HOUSE_LOOT_ALLOWED.get()) { // they know you: allowed, but they mind a little
                Reputation.addGoodwill(player, owner, -Math.min(MAX_PER_VISIT, taken * GOODWILL_PER_ITEM), 0);
                return;
            }
        }
        Entity center = nearestVillager(player);
        Reputation.addVillageGoodwill(player, center == null ? player : center, -Math.min(MAX_PER_VISIT, taken * GOODWILL_PER_ITEM));
        // Someone watching? Then it's theft (life.law).
        dev.villagechatter.life.law.Law.commit(player, dev.villagechatter.life.law.CrimeKind.THEFT, null,
                stolen(opened.contents(), contents(event.getContainer())), opened.pos());
    }

    /** Whose house this container is in: the villager whose bed is nearest (within 8 blocks, 4 up or down). */
    static Villager owner(ServerLevel level, BlockPos pos) {
        Villager best = null;
        double bestDist = Double.MAX_VALUE;
        for (Villager v : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(pos).inflate(48), v -> !v.isBaby())) {
            Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
            if (home.isEmpty() || !home.get().dimension().equals(level.dimension())) continue;
            BlockPos bed = home.get().pos();
            if (Math.abs(bed.getY() - pos.getY()) > 4) continue;
            double d = bed.distSqr(pos);
            if (d <= 8 * 8 && d < bestDist) {
                best = v;
                bestDist = d;
            }
        }
        return best;
    }

    private static java.util.List<net.minecraft.world.item.ItemStack> contents(AbstractContainerMenu menu) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) out.add(slot.getItem().copy());
        }
        return out;
    }

    /** What's missing now compared to before. */
    private static java.util.List<net.minecraft.world.item.ItemStack> stolen(java.util.List<net.minecraft.world.item.ItemStack> before,
                                                                             java.util.List<net.minecraft.world.item.ItemStack> after) {
        java.util.List<net.minecraft.world.item.ItemStack> left = new java.util.ArrayList<>();
        for (var s : after) left.add(s.copy());
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (var was : before) {
            int missing = was.getCount();
            for (var now : left) {
                if (missing == 0) break;
                if (!net.minecraft.world.item.ItemStack.isSameItemSameComponents(was, now)) continue;
                int match = Math.min(missing, now.getCount());
                now.shrink(match);
                missing -= match;
            }
            if (missing > 0) out.add(was.copyWithCount(missing));
        }
        return out;
    }

    /** Items in the container's own slots (not the player's inventory below it). */
    private static int count(AbstractContainerMenu menu) {
        int total = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) total += slot.getItem().getCount();
        }
        return total;
    }

    private static Entity nearestVillager(ServerPlayer player) {
        Villager best = null;
        for (Villager v : LivingVillage.villagersNear(player.serverLevel(), player, 48, false)) {
            if (best == null || v.distanceToSqr(player) < best.distanceToSqr(player)) best = v;
        }
        return best;
    }

    /**
     * Containers players placed themselves (those are theirs; taking from them is fine, and piglin quests skip them).
     * Saved per dimension. The one owner of "villagechatter_placed_containers": 0.16.9 also had a second class under that
     * name, saving under "pos", so both are read back.
     */
    public static final class PlacedContainers extends SavedData {
        private final LongOpenHashSet positions = new LongOpenHashSet();

        public static PlacedContainers get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlacedContainers::new, PlacedContainers::load, null),
                    "villagechatter_placed_containers");
        }

        private static PlacedContainers load(CompoundTag tag, HolderLookup.Provider registries) {
            PlacedContainers data = new PlacedContainers();
            for (long l : tag.getLongArray("positions")) data.positions.add(l);
            for (long l : tag.getLongArray("pos")) data.positions.add(l);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("positions", positions.toLongArray());
            return tag;
        }

        public void remember(BlockPos pos) {
            if (positions.add(pos.asLong())) setDirty();
        }

        public void forget(BlockPos pos) {
            if (positions.remove(pos.asLong())) setDirty();
        }

        public boolean placedByPlayer(BlockPos pos) {
            return positions.contains(pos.asLong());
        }
    }
}

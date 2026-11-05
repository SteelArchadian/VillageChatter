package dev.villagechatter.reputation.faction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Piglin quests, thief-flavoured (Factions v2, item 7). Asking a piglin for work can get you a sabotage job (raiding a
 * pillager structure is one), or:
 * <ul>
 *   <li><b>Bring us gold:</b> a few ingots, handed over when you next talk to a piglin. Piglins offer work twice a day.</li>
 *   <li><b>The heirloom:</b> asked in the Nether, a piglin tells of a golden heirloom some villager walked off with, in the
 *   village nearest the Overworld spot that matches where you stand (x8). When you get there it's in one of the
 *   village's ORIGINAL containers (made with the world, never one a player placed: chests, barrels, modded ones).
 *   Keep it, or bring it back for their thanks.</li>
 * </ul>
 */
public final class PiglinQuests {
    private PiglinQuests() {}

    static final String HEIRLOOM_TAG = "villagechatter_heirloom";
    static final int HEIRLOOM_DAYS = 7, PLANT_RANGE = 96, SEARCH = 80;

    /** A piglin's own quest, or empty for an ordinary sabotage job. */
    static Optional<Sabotage.Job> roll(ServerPlayer player, long now) {
        var random = player.getRandom();
        long expires = now + ChatterConfig.SABOTAGE_DAYS.get() * 24000L;
        int pick = random.nextInt(3);
        if (pick == 0 && player.level().dimension() == Level.NETHER) {
            Optional<BlockPos> village = villageAbove(player);
            if (village.isPresent()) {
                return Optional.of(new Sabotage.Job(Faction.PIGLIN, Faction.VILLAGE, Sabotage.Kind.HEIRLOOM, 1, 0,
                        now + HEIRLOOM_DAYS * 24000L, false, village.get().asLong()));
            }
        }
        if (pick <= 1) {
            int gold = 4 * (1 + random.nextInt(4));
            return Optional.of(new Sabotage.Job(Faction.PIGLIN, Faction.PIGLIN, Sabotage.Kind.GOLD, gold, 0, expires, false, 0));
        }
        return Optional.empty();
    }

    /** The village nearest the Overworld point matching this Nether position (x8). */
    static Optional<BlockPos> villageAbove(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        BlockPos above = new BlockPos(player.getBlockX() * 8, 64, player.getBlockZ() * 8);
        return Optional.ofNullable(overworld.findNearestMapStructure(StructureTags.VILLAGE, above, 48, false));
    }

    // ------------------------------------------------------------------ the heirloom

    public static ItemStack heirloom(ServerPlayer owner) {
        ItemStack stack = new ItemStack(Items.CLOCK);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.villagechatter.piglin_heirloom")
                .withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag tag = new CompoundTag();
        tag.putUUID(HEIRLOOM_TAG, owner.getUUID());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isHeirloom(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(HEIRLOOM_TAG);
    }

    static boolean hasHeirloom(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) if (isHeirloom(s)) return true;
        return isHeirloom(player.getOffhandItem());
    }

    static void takeHeirloom(ServerPlayer player) {
        ContainerHelper.clearOrCountMatchingItems(player.getInventory(), PiglinQuests::isHeirloom, 1, false);
    }

    static boolean hasGold(ServerPlayer player, int n) {
        return player.getInventory().countItem(Items.GOLD_INGOT) >= n;
    }

    static void takeGold(ServerPlayer player, int n) {
        ContainerHelper.clearOrCountMatchingItems(player.getInventory(), s -> s.is(Items.GOLD_INGOT), n, false);
    }

    /** Once you're near the village, the heirloom turns up in one of its original containers. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 40 != 0) return;
        if (player.level().dimension() != Level.OVERWORLD) return;
        Optional<Sabotage.Job> job = Sabotage.job(player);
        if (job.isEmpty() || job.get().kind() != Sabotage.Kind.HEIRLOOM || !job.get().accepted() || job.get().progress() > 0) return;
        BlockPos village = BlockPos.of(job.get().at());
        if (!player.blockPosition().closerThan(village, PLANT_RANGE)) return;
        if (plant(player.serverLevel(), village, SEARCH, heirloom(player), player.getRandom())) {
            Sabotage.Job j = job.get();
            Sabotage.save(player, Optional.of(new Sabotage.Job(j.issuer(), j.target(), j.kind(), j.count(), 1, j.expires(), true, j.at())),
                    Sabotage.book(player).offeredDay());
            player.displayClientMessage(Component.translatable("villagechatter.piglin.quest.heirloom_near"), true);
        }
    }

    /** Puts {@code item} in a random original container with room, in loaded chunks within {@code radius} of {@code center}. */
    static boolean plant(ServerLevel level, BlockPos center, int radius, ItemStack item, net.minecraft.util.RandomSource random) {
        List<Container> found = originals(level, center, radius);
        while (!found.isEmpty()) {
            Container c = found.remove(random.nextInt(found.size()));
            List<Integer> empty = new ArrayList<>();
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) empty.add(i);
            if (empty.isEmpty()) continue;
            c.setItem(empty.get(random.nextInt(empty.size())), item);
            c.setChanged();
            return true;
        }
        return false;
    }

    /** Containers made with the world (not placed by a player) in loaded chunks within {@code radius}. */
    static List<Container> originals(ServerLevel level, BlockPos center, int radius) {
        List<Container> out = new ArrayList<>();
        var placed = dev.villagechatter.life.Misdeeds.PlacedContainers.get(level);
        int r = (radius >> 4) + 1;
        for (int cx = (center.getX() >> 4) - r; cx <= (center.getX() >> 4) + r; cx++) {
            for (int cz = (center.getZ() >> 4) - r; cz <= (center.getZ() >> 4) + r; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof Container c && be.getBlockPos().closerThan(center, radius) && !placed.placedByPlayer(be.getBlockPos())) out.add(c);
                }
            }
        }
        return out;
    }
}

package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.quest.Discounts;
import dev.villagechatter.reputation.Standing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Villagers who like you look out for you, now and then:
 *  - a mason sees you building with stone or brick and running low (or going through a lot) and tosses you some,
 *  - when you're badly hurt during the day in a village that likes you, someone tosses you food (a cleric, a potion),
 *  - a close friend sometimes has something for you: a thank-you, a gift from their trade and a day's discount.
 * All rare, all with cooldowns.
 */
public final class Kindness {
    private Kindness() {}

    // Thresholds on the standing scale (see reputation.Standing: +20 "Friend", +50 "Close Friend").
    private static final double FRIEND = 20, CLOSE_FRIEND = 50;

    // ------------------------------------------------------------------ the mason's spare stone

    private static final double MASON_CHANCE = 0.04;
    private static final int MASON_COOLDOWN = 24000, LOW_STACK = 16, LOTS_PLACED = 48, TOSS = 12;
    /** "player|block" -> [count placed recently, window start] */
    private static final Map<String, int[]> PLACED = new HashMap<>();
    private static final Map<String, Integer> MASON_NEXT = new HashMap<>();

    static void onBuild(ServerPlayer player, BlockState state, BlockPos pos) {
        if (!ChatterConfig.VILLAGER_KINDNESS.get()) return;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (!(path.contains("brick") || path.contains("stone") || path.contains("cobble") || path.contains("deepslate"))) return;
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) return;
        int now = LivingVillage.tick();
        String key = player.getUUID() + "|" + path;
        int[] tally = PLACED.computeIfAbsent(key, k -> new int[] {0, now});
        if (now - tally[1] > 20 * 60 * 10) {
            tally[0] = 0;
            tally[1] = now;
        }
        tally[0]++;
        boolean running = player.getInventory().countItem(item) <= LOW_STACK || tally[0] >= LOTS_PLACED;
        if (!running) return;
        ServerLevel level = player.serverLevel();
        if (level.random.nextDouble() >= MASON_CHANCE) return;
        for (Villager v : LivingVillage.villagersNear(level, player, 24, true)) {
            if (!"mason".equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()).getPath())) continue;
            String who = v.getUUID() + "|" + player.getUUID();
            if (MASON_NEXT.getOrDefault(who, 0) > now || Standing.score(player, v) < FRIEND || !v.hasLineOfSight(player)) continue;
            MASON_NEXT.put(who, now + MASON_COOLDOWN);
            BehaviorUtils.throwItem(v, new ItemStack(item, TOSS), player.position());
            LivingVillage.babble(v, 3, 0, 0.05F, 0.7F);
            LivingVillage.say(player, v, Component.translatable("villagechatter.life.mason." + level.random.nextInt(3)));
            tally[0] = 0;
            return;
        }
    }

    // ------------------------------------------------------------------ help when you're hurt

    private static final int AID_COOLDOWN = 24000;
    private static final Map<UUID, Integer> AID_NEXT = new HashMap<>();

    static void tickPlayer(ServerPlayer player, int now) {
        if (!ChatterConfig.VILLAGER_KINDNESS.get() || now % 40 != 0) return;
        if (player.getHealth() > Math.max(6F, player.getMaxHealth() * 0.3F) || player.isCreative() || player.isSpectator()) return;
        if (AID_NEXT.getOrDefault(player.getUUID(), 0) > now) return;
        ServerLevel level = player.serverLevel();
        long time = level.getDayTime() % 24000;
        if (time < 1000 || time > 11500) return; // during the working day
        var village = Standing.villageScore(player, level, player.blockPosition());
        if (village.isEmpty() || village.getAsDouble() < FRIEND) return;
        Villager helper = null;
        for (Villager v : LivingVillage.villagersNear(level, player, 16, true)) {
            if (!LivingVillage.idle(v) || !v.hasLineOfSight(player)) continue;
            if (helper == null || priority(v) > priority(helper)) helper = v;
        }
        if (helper == null) return;
        AID_NEXT.put(player.getUUID(), now + AID_COOLDOWN);
        BehaviorUtils.throwItem(helper, aidFor(helper), player.position());
        LivingVillage.babble(helper, 4, 0, 0.1F, 0.8F);
        LivingVillage.say(player, helper, Component.translatable("villagechatter.life.aid." + level.random.nextInt(3)));
    }

    private static String trade(Villager v) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()).getPath();
    }

    private static int priority(Villager v) {
        return switch (trade(v)) {
            case "cleric" -> 3;
            case "farmer", "butcher", "fisherman" -> 2;
            default -> 1;
        };
    }

    private static ItemStack aidFor(Villager v) {
        return switch (trade(v)) {
            case "cleric" -> PotionContents.createItemStack(Items.POTION, Potions.HEALING);
            case "butcher" -> new ItemStack(Items.COOKED_PORKCHOP, 2);
            case "fisherman" -> new ItemStack(Items.COOKED_COD, 2);
            case "farmer" -> new ItemStack(Items.BREAD, 3);
            default -> new ItemStack(Items.BREAD, 2);
        };
    }

    // ------------------------------------------------------------------ a close friend's surprise

    public static final String FRIEND_GIFT = "friend_gift";
    private static final double FRIEND_GIFT_CHANCE = 0.3;
    private static final long GIFT_COOLDOWN_MIN = 2 * 24000, GIFT_COOLDOWN_MAX = 3 * 24000, REROLL = 2400;

    /** Per player: villager uuid -> game time when they might next surprise you. */
    public static final class Ledger {
        public static final Codec<Ledger> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG)
                .xmap(m -> new Ledger(new HashMap<>(m)), l -> l.next);
        final Map<String, Long> next;

        public Ledger() {
            this(new HashMap<>());
        }

        private Ledger(Map<String, Long> next) {
            this.next = next;
        }
    }

    /** Does this villager have a surprise for you right now? (Rolled once per window, not on every click.) */
    public static boolean friendGiftReady(ServerPlayer player, Villager villager) {
        if (!ChatterConfig.VILLAGER_KINDNESS.get() || villager.isBaby() || Standing.score(player, villager) < CLOSE_FRIEND) return false;
        Ledger ledger = player.getData(ChatterAttachments.FRIEND_GIFTS);
        long now = player.serverLevel().getGameTime();
        String key = villager.getUUID().toString();
        if (ledger.next.getOrDefault(key, 0L) > now) return false;
        if (player.getRandom().nextDouble() >= FRIEND_GIFT_CHANCE) {
            ledger.next.put(key, now + REROLL);
            return false;
        }
        return true;
    }

    /** "I just want to thank you. You're a good friend." A gift from their trade and 10% off for the day. */
    public static void giveFriendGift(ServerPlayer player, Villager villager) {
        long now = player.serverLevel().getGameTime();
        player.getData(ChatterAttachments.FRIEND_GIFTS).next.put(villager.getUUID().toString(),
                now + GIFT_COOLDOWN_MIN + (long) (player.getRandom().nextDouble() * (GIFT_COOLDOWN_MAX - GIFT_COOLDOWN_MIN)));
        ItemStack gift = switch (trade(villager)) {
            case "fisherman" -> new ItemStack(Items.COOKED_COD, 4);
            case "butcher" -> new ItemStack(Items.COOKED_BEEF, 3);
            case "farmer" -> new ItemStack(Items.BREAD, 4);
            case "cleric" -> PotionContents.createItemStack(Items.POTION, Potions.HEALING);
            case "toolsmith", "armorer", "weaponsmith", "miner" -> new ItemStack(Items.COAL, 8);
            case "librarian" -> new ItemStack(Items.BOOK, 2);
            case "cartographer" -> new ItemStack(Items.PAPER, 8);
            case "mason" -> new ItemStack(Items.BRICKS, 16);
            case "shepherd" -> new ItemStack(Items.WHITE_WOOL, 8);
            case "fletcher" -> new ItemStack(Items.ARROW, 16);
            case "leatherworker" -> new ItemStack(Items.LEATHER, 4);
            case "florist" -> new ItemStack(Items.POPPY, 8);
            case "forester" -> new ItemStack(Items.OAK_SAPLING, 6);
            case "hunter" -> new ItemStack(Items.COOKED_PORKCHOP, 3);
            case "oceanographer" -> new ItemStack(Items.COOKED_SALMON, 3);
            default -> new ItemStack(Items.EMERALD, 2);
        };
        if (!player.getInventory().add(gift)) player.drop(gift, false);
        Discounts.grant(player, villager, 1.0, 0.10);
    }
}

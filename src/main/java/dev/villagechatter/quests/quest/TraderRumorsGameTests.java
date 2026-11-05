package dev.villagechatter.quests.quest;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Wandering traders' rumors: free ones told once, guarded ones talked out of them or paid for. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class TraderRumorsGameTests {
    private TraderRumorsGameTests() {}

    private static WanderingTrader trader(GameTestHelper helper) {
        WanderingTrader trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(2, 1, 2));
        trader.setNoAi(true);
        return trader;
    }

    private static TraderRumors.Entry place(GameTestHelper helper, int dx, int value, boolean guarded) {
        BlockPos pos = helper.absolutePos(new BlockPos(2 + dx, 0, 2));
        return new TraderRumors.Entry("minecraft:desert_pyramid", false, pos.getX(), pos.getZ(), pos.asLong(), value, guarded);
    }

    private static boolean told(ServerPlayer player, TraderRumors.Entry entry) {
        return player.getData(QuestRegistries.RUMORS_TOLD).positions.contains(entry.key());
    }

    /** Right-clicking a wandering trader opens the talk, not the trade screen. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderRightClickTalks(GameTestHelper helper) {
        WanderingTrader trader = trader(helper);
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "customer"));
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 2));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        // Still holding the egg it was spawned with: traders have no young, so that's no reason to skip the talk.
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.WANDERING_TRADER_SPAWN_EGG));
        player.interactOn(trader, net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.assertTrue(trader.getTradingPlayer() == null, "no trade screen");
        helper.assertTrue(dev.villagechatter.server.ConversationManager.isTalkingTo(player, trader), "the talk opens");
        helper.succeed();
    }

    /** A free rumor is told once; then the trader has nothing new. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderFreeRumorOnce(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        WanderingTrader trader = trader(helper);
        var entry = place(helper, 900, 1, false);
        TraderRumors.add(trader, entry);
        var first = TraderRumors.handle(player, trader, TraderRumors.OPTION);
        helper.assertTrue(first != null && told(player, entry), "the rumor is told");
        helper.assertTrue(first.options().contains(TraderRumors.OPTION), "and you can keep asking");
        helper.assertTrue(TraderRumors.next(TraderRumors.stock(trader), player.getData(QuestRegistries.RUMORS_TOLD)) == null, "but there's nothing new");
        helper.succeed();
    }

    /** One trader tells one player only so many places, then keeps the rest back. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderRumorCap(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        WanderingTrader trader = trader(helper);
        int cap = QuestConfig.TRADER_RUMOR_CAP.get();
        java.util.List<TraderRumors.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i <= cap; i++) {
            var entry = place(helper, 2100 + i * 400, 1, false);
            entries.add(entry);
            TraderRumors.add(trader, entry);
        }
        for (int i = 0; i < cap; i++) TraderRumors.handle(player, trader, TraderRumors.OPTION);
        helper.assertTrue(entries.subList(0, cap).stream().allMatch(e -> told(player, e)), "told up to the cap");
        var last = TraderRumors.handle(player, trader, TraderRumors.OPTION);
        helper.assertTrue(last != null && !told(player, entries.get(cap)), "but not past it");
        helper.assertTrue(TraderRumors.tapped(TraderRumors.stock(trader), player.getUUID().toString()), "the trader is tapped");
        helper.succeed();
    }

    /** A guarded rumor names the place but not where; a failed try makes the next much harder; a good one tells all. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderPersuade(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        WanderingTrader trader = trader(helper);
        var entry = place(helper, 1500, 2, true);
        TraderRumors.add(trader, entry);
        var offer = TraderRumors.handle(player, trader, TraderRumors.OPTION);
        helper.assertTrue(offer.options().contains(TraderRumors.PERSUADE) && offer.options().contains(TraderRumors.BRIBE)
                && !told(player, entry), "guarded: persuade or pay");
        double chance = QuestConfig.TRADER_PERSUADE_CHANCE.get(), after = QuestConfig.TRADER_PERSUADE_AFTER_FAIL.get();
        try {
            QuestConfig.TRADER_PERSUADE_CHANCE.set(0.0);
            TraderRumors.handle(player, trader, TraderRumors.PERSUADE);
            helper.assertTrue(!told(player, entry) && TraderRumors.stock(trader).fails.get(player.getUUID().toString()) == 1, "talked down once");
            QuestConfig.TRADER_PERSUADE_CHANCE.set(0.4);
            helper.assertTrue(TraderRumors.persuadeChance(1) < TraderRumors.persuadeChance(0) / 2, "the next try is much harder");
            QuestConfig.TRADER_PERSUADE_CHANCE.set(1.0);
            QuestConfig.TRADER_PERSUADE_AFTER_FAIL.set(1.0);
            TraderRumors.handle(player, trader, TraderRumors.PERSUADE);
            helper.assertTrue(told(player, entry), "talked round");
        } finally {
            QuestConfig.TRADER_PERSUADE_CHANCE.set(chance);
            QuestConfig.TRADER_PERSUADE_AFTER_FAIL.set(after);
        }
        helper.succeed();
    }

    /** Paying always works, and the price follows what the place is worth. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderBribe(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        WanderingTrader trader = trader(helper);
        var entry = place(helper, 2200, 3, true);
        TraderRumors.add(trader, entry);
        int price = TraderRumors.price(entry);
        helper.assertTrue(price == 3 * QuestConfig.TRADER_BRIBE_PER_VALUE.get(), "priced by value");
        TraderRumors.handle(player, trader, TraderRumors.BRIBE);
        helper.assertFalse(told(player, entry), "no emeralds, no rumor");
        player.getInventory().add(new ItemStack(Items.EMERALD, price + 2));
        TraderRumors.handle(player, trader, TraderRumors.BRIBE);
        helper.assertTrue(told(player, entry), "paid: told");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 2, "and the price was taken");
        helper.succeed();
    }

    /** Villages aren't worth guarding; temples and mansions are, and only valuable places ever get guarded. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void traderValues(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        java.util.function.Function<String, Integer> value = id -> TraderRumors.value(registry.getHolderOrThrow(
                ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace(id))));
        helper.assertTrue(value.apply("village_plains") == 0 && value.apply("shipwreck") == 1, "villages 0, shipwrecks 1");
        helper.assertTrue(value.apply("desert_pyramid") == 2 && value.apply("mansion") == 3, "temples 2, mansions 3");
        RandomSource random = RandomSource.create(1);
        double share = QuestConfig.TRADER_GUARDED_SHARE.get();
        try {
            QuestConfig.TRADER_GUARDED_SHARE.set(1.0);
            helper.assertTrue(TraderRumors.guard(2, random) && !TraderRumors.guard(0, random), "valuable ones only");
        } finally {
            QuestConfig.TRADER_GUARDED_SHARE.set(share);
        }
        helper.succeed();
    }
}

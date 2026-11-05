package dev.villagechatter.reputation.faction;

import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** How pillagers take a raid's end: praise for their side when they win, scorn when they lose, a stand-down otherwise. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RaidOutcomeGameTests {
    private RaidOutcomeGameTests() {}

    private static ServerPlayer player(GameTestHelper helper, String name) {
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void raidWonOnTheirSidePraises(GameTestHelper helper) {
        ServerPlayer player = player(helper, "raidfriend");
        Factions.adjust(player, Faction.PILLAGER, 200);
        Factions.adjust(player, Faction.PILLAGER, -10); // off the top, so a gain shows
        double before = FactionBook.pillager(player);
        helper.assertTrue(RaidOutcome.settle(player, true), "their side, they won: something to say");
        helper.assertTrue(RaidOutcome.mood(player) == RaidOutcome.Mood.PRAISE, "pillagers praise you");
        helper.assertTrue(FactionBook.pillager(player) > before, "and think better of you");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void raidLostOnTheirSideScorns(GameTestHelper helper) {
        ServerPlayer player = player(helper, "fairweather");
        Factions.adjust(player, Faction.PILLAGER, 200);
        double before = FactionBook.pillager(player);
        helper.assertTrue(RaidOutcome.settle(player, false), "their side, they lost: something to say");
        helper.assertTrue(RaidOutcome.mood(player) == RaidOutcome.Mood.SCORN, "pillagers scorn you");
        helper.assertTrue(FactionBook.pillager(player) < before, "and think less of you");
        helper.succeed();
    }

    /** Fought them and they won: their captain names a toll (here only your sword qualifies), you hand it over, they leave you be. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void raidLostAgainstThemSurrender(GameTestHelper helper) {
        ServerPlayer player = player(helper, "defender");
        Factions.adjust(player, Faction.PILLAGER, -200);
        Pillager captain = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        captain.setItemSlot(EquipmentSlot.HEAD,
                Raid.getLeaderBannerInstance(helper.getLevel().registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
        player.getInventory().selected = 0;
        player.getInventory().items.set(0, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(RaidOutcome.settle(player, true), "the captain should make you stand down");
        helper.assertTrue(RaidOutcome.spared(player), "nobody shoots while the captain talks");
        helper.assertTrue(RaidOutcome.handle(player, captain, RaidOutcome.SURRENDER), "surrender is the captain's to take");
        helper.assertTrue(player.getInventory().countItem(Items.IRON_SWORD) == 0, "the sword is the toll (as items or as the weapon)");
        helper.assertTrue(RaidOutcome.spared(player), "then they leave you be");
        RaidOutcome.forget(player.getUUID());
        helper.succeed();
    }

    /** Nothing to pay with: the captain offers no terms, and they fight. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void raidLostAgainstThemPoor(GameTestHelper helper) {
        ServerPlayer player = player(helper, "pauper");
        Factions.adjust(player, Faction.PILLAGER, -200);
        Pillager captain = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        helper.assertTrue(RaidOutcome.settle(player, true), "the captain should face you");
        helper.assertFalse(RaidOutcome.spared(player), "empty pockets: no terms, no mercy");
        helper.assertTrue(captain.getTarget() == player, "and they come for you");
        RaidOutcome.forget(player.getUUID());
        helper.succeed();
    }

    /** Armor only, weights favouring armor: the captain wants the clothes off your back. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void raidTollArmor(GameTestHelper helper) {
        ServerPlayer player = player(helper, "plated");
        Factions.adjust(player, Faction.PILLAGER, -200);
        helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        RaidOutcome.Demand demand = RaidOutcome.demand(player, helper.getLevel());
        helper.assertTrue(demand != null && demand.kind() == RaidOutcome.TollKind.ARMOR, "only armor to take: armor it is");
        helper.succeed();
    }
}

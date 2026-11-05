package dev.villagechatter.life;

import java.util.List;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Living villages: chats between villagers, and a close friend's surprise gift. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class LifeGameTests {
    private LifeGameTests() {}

    /** Two villagers chatting are busy; walking in on them breaks it up for both. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void chatAndInterrupt(GameTestHelper helper) {
        Villager a = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        Villager b = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 2));
        a.setNoAi(true);
        b.setNoAi(true);
        AmbientChatter.start(helper.getLevel(), List.of(a, b), false, LivingVillage.tick());
        helper.assertTrue(AmbientChatter.isChatting(a) && AmbientChatter.isChatting(b), "both should be chatting");
        helper.assertFalse(LivingVillage.idle(a), "a chatting villager isn't free to wander off");
        AmbientChatter.interrupt(a);
        helper.assertFalse(AmbientChatter.isChatting(a) || AmbientChatter.isChatting(b), "interrupting breaks up the chat");
        helper.succeed();
    }

    /** Guards judge gear by how good it is: better weapons and armor are worth more, worn-out gear less. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void guardGearValue(GameTestHelper helper) {
        var hand = net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        var chest = net.minecraft.world.entity.EquipmentSlot.CHEST;
        double iron = GuardGear.value(new net.minecraft.world.item.ItemStack(Items.IRON_SWORD), hand);
        double diamond = GuardGear.value(new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD), hand);
        var worn = new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        helper.assertTrue(diamond > iron && iron > 0, "a diamond sword beats an iron one");
        helper.assertTrue(GuardGear.value(worn, hand) < diamond, "a nearly broken sword is worth less");
        helper.assertTrue(GuardGear.value(new net.minecraft.world.item.ItemStack(Items.IRON_CHESTPLATE), chest)
                > GuardGear.value(new net.minecraft.world.item.ItemStack(Items.LEATHER_CHESTPLATE), chest), "iron beats leather");
        helper.assertTrue(GuardGear.value(new net.minecraft.world.item.ItemStack(Items.BREAD), hand) == 0, "bread isn't gear");
        helper.succeed();
    }

    /** A close friend's surprise: a gift from their trade, a day's discount, then a cooldown of a few days. */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void friendGift(GameTestHelper helper) {
        Villager farmer = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        farmer.setNoAi(true);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        farmer.getGossips().add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20); // 20 major = +100: a close friend

        Kindness.giveFriendGift(player, farmer);
        helper.assertTrue(player.getInventory().countItem(Items.BREAD) == 4, "a farmer friend gives bread");
        helper.assertTrue(player.getData(QuestRegistries.DISCOUNTS).byVillager.containsKey(farmer.getUUID()), "and a day's discount");
        long next = player.getData(ChatterAttachments.FRIEND_GIFTS).next.get(farmer.getUUID().toString());
        helper.assertTrue(next - helper.getLevel().getGameTime() >= 2 * 24000, "then waits at least two days");
        helper.assertFalse(Kindness.friendGiftReady(player, farmer), "no second surprise right away");
        helper.succeed();
    }
}

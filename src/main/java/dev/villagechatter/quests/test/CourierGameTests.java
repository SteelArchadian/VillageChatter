package dev.villagechatter.quests.test;

import java.util.UUID;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.Courier;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Courier errands: carrying a package to a profession in another village. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class CourierGameTests {
    private CourierGameTests() {}

    private record Setup(Villager recipient, ServerPlayer player, PlayerQuests quests, ActiveQuest quest, QuestDefinition def) {}

    /** A farmer in "the other village" and a player carrying an egg package for a farmer there. */
    @SuppressWarnings("removal")
    private static Setup carrying(GameTestHelper helper, VillagerProfession profession) {
        Villager recipient = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        recipient.setNoAi(true);
        recipient.setVillagerData(recipient.getVillagerData().setProfession(profession));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("courier/egg_delivery"));
        helper.assertTrue(def != null && def.isCourier(), "egg_delivery courier should be loaded");
        BlockPos village = recipient.blockPosition();
        long now = helper.getLevel().getGameTime();
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), UUID.randomUUID(), Component.literal("Bartholomew"),
                helper.getLevel().dimension(), village.offset(-600, 0, -600), village, "village:minecraft:farmer",
                now, now + def.deadlineTicks(), ActiveQuest.State.ACTIVE, false, 0, 10);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        quests.quests.add(quest);
        player.getInventory().add(Courier.makePackage(def, quest));
        return new Setup(recipient, player, quests, quest, def);
    }

    /** The right trade in the right village takes the package: paid, and the trip ends or a reply comes back. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierDelivered(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.FARMER);
        helper.assertTrue(Courier.deliverable(s.player, s.recipient) != null, "the farmer should take the eggs");
        Component said = Courier.deliver(s.player, s.recipient);
        helper.assertTrue(said != null, "delivery should get a thank-you");
        helper.assertTrue(s.player.getInventory().countItem(Items.EGG) == 0, "the package should be handed over");
        helper.assertTrue(s.player.getInventory().countItem(Items.EMERALD) >= 2, "should be paid at least two emeralds");
        boolean reply = s.player.getInventory().countItem(Items.PAPER) == 1;
        helper.assertTrue(reply ? s.quest.itemDropped && s.quests.quests.contains(s.quest) : s.quests.quests.isEmpty(),
                "either the trip ends, or a reply letter goes back to the sender");
        helper.succeed();
    }

    /** Wrong trade: a librarian won't take the farmer's eggs. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierWrongRecipient(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.LIBRARIAN);
        helper.assertTrue(Courier.deliverable(s.player, s.recipient) == null, "a librarian shouldn't take a farmer's package");
        helper.assertTrue(Courier.deliver(s.player, s.recipient) == null, "nothing should be delivered");
        helper.assertTrue(s.player.getInventory().countItem(Items.EGG) == s.quest.count, "the package stays with the player");
        helper.succeed();
    }

    /** Package eggs belong to the trip: they don't count toward someone else's "bring me eggs" errand. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierPackageNotErrandItems(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.FARMER);
        QuestDefinition.Request eggs = new QuestDefinition.Request(net.minecraft.resources.ResourceLocation.withDefaultNamespace("egg"),
                null, null, 1, 1);
        helper.assertTrue(QuestManager.countMatching(s.player, eggs) == 0, "package eggs shouldn't count for errands");
        s.player.getInventory().add(new ItemStack(Items.EGG, 3));
        helper.assertTrue(QuestManager.countMatching(s.player, eggs) == 3, "ordinary eggs still count");
        helper.succeed();
    }

    /** Quitting the trip: the package turns back into ordinary eggs you can keep and use. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierQuitFreesPackage(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.FARMER);
        QuestManager.quit(s.player, s.quest, null);
        QuestDefinition.Request eggs = new QuestDefinition.Request(net.minecraft.resources.ResourceLocation.withDefaultNamespace("egg"),
                null, null, 1, 1);
        helper.assertTrue(QuestManager.countMatching(s.player, eggs) == s.quest.count, "the eggs should be ordinary eggs again");
        helper.assertTrue(Courier.deliverable(s.player, s.recipient) == null, "a quit trip can't be delivered");
        helper.succeed();
    }

    /** With the destination loaded, the trip is tied to one specific villager: only they can take it. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierNamedRecipient(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.FARMER);
        Villager otherFarmer = helper.spawn(EntityType.VILLAGER, new BlockPos(4, 1, 2));
        otherFarmer.setNoAi(true);
        otherFarmer.setVillagerData(otherFarmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        Courier.tryBindNow(s.player, s.quest);
        helper.assertTrue(s.quest.buddy != null, "a specific recipient should be picked");
        Villager bound = s.quest.buddy.equals(s.recipient.getUUID()) ? s.recipient : otherFarmer;
        Villager other = bound == s.recipient ? otherFarmer : s.recipient;
        helper.assertTrue(Courier.recipientPersonName(s.player, s.quest) != null, "the recipient's name should be remembered");
        helper.assertTrue(Courier.deliverable(s.player, other) == null, "another farmer shouldn't take it");
        helper.assertTrue(Courier.deliverable(s.player, bound) != null, "the named recipient should take it");
        helper.succeed();
    }

    /** The recipient dies before you arrive: the trip fails, and the sender owes you something for your time. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void courierRecipientDied(GameTestHelper helper) {
        Setup s = carrying(helper, VillagerProfession.FARMER);
        Courier.tryBindNow(s.player, s.quest);
        helper.assertTrue(s.recipient.getUUID().equals(s.quest.buddy), "the only farmer should be the recipient");
        Courier.onVillagerDeath(helper.getLevel(), s.recipient);
        helper.assertTrue(s.quest.state == ActiveQuest.State.FAILED, "the trip should fail");
        helper.assertTrue(s.quest.compensationOwed(), "the sender should owe compensation");
        helper.succeed();
    }
}

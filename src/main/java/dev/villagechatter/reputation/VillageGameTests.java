package dev.villagechatter.reputation;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.HeirloomData;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestDialogue;
import dev.villagechatter.quests.quest.QuestManager;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One town per bell cluster; quests that go wrong through nobody's fault; rough locations. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class VillageGameTests {
    private VillageGameTests() {}

    /** Two bells in one town are one village, whichever bell you ask about. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void twoBellsOneVillage(GameTestHelper helper) {
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.BELL);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.BELL);
        BlockPos a = helper.absolutePos(new BlockPos(0, 1, 0)), b = helper.absolutePos(new BlockPos(4, 1, 4));
        List<BlockPos> fromA = Villages.bellCluster(helper.getLevel(), a), fromB = Villages.bellCluster(helper.getLevel(), b);
        helper.assertTrue(fromA.size() >= 2 && fromA.contains(b), "both bells belong to one town, got " + fromA);
        helper.assertTrue(fromA.get(0).equals(fromB.get(0)), "same village anchor from either bell");
        helper.succeed();
    }

    /** The heirloom burns up: the quest fails through nobody's fault and the giver pays a little for your time. */
    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("removal")
    public static void lostHeirloomIsCompensated(GameTestHelper helper) {
        Villager giver = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        giver.setVillagerData(giver.getVillagerData().setProfession(VillagerProfession.WEAPONSMITH));
        giver.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("family_sword"));
        QuestManager.tryOffer(giver, def);
        QuestManager.answer(player, giver.getUUID(), QuestManager.ACCEPT);
        ActiveQuest quest = player.getData(QuestRegistries.PLAYER_QUESTS).quests.get(0);

        ItemStack sword = def.createItem(helper.getLevel().registryAccess()).orElseThrow();
        sword.set(QuestRegistries.HEIRLOOM.get(), new HeirloomData(quest.instance, player.getUUID()));
        ItemEntity dropped = new ItemEntity(helper.getLevel(), pos.x, pos.y, pos.z, sword);
        helper.getLevel().addFreshEntity(dropped);
        dropped.kill(); // e.g. it fell in lava

        helper.assertTrue(quest.compensationOwed(), "losing the heirloom fails the quest with compensation owed, state " + quest.state);
        helper.assertTrue(quest.failReason.startsWith("villagequests.fail.item_lost"), "reason: item lost");
        QuestDialogue.Opening open = QuestDialogue.open(player, giver);
        helper.assertTrue(open.greeting().isPresent(), "the giver brings it up");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 2, "mediocre compensation");
        helper.assertTrue(quest.failReason.startsWith("villagequests.fail.item_lost_paid"), "journal notes it was paid");

        // The note and the journal give an area, not the exact spot.
        String rough = QuestText.roughCoords(quest.target).getString();
        helper.assertTrue(rough.equals(QuestText.roughCoords(quest.target).getString()), "rough location is stable");
        helper.succeed();
    }
}

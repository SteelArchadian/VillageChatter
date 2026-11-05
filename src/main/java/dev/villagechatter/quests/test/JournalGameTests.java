package dev.villagechatter.quests.test;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.journal.QuestJournal;
import dev.villagechatter.quests.journal.QuestNetwork;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestDialogue;
import dev.villagechatter.quests.quest.QuestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Quitting, failures in the journal, and build requests. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class JournalGameTests {
    private JournalGameTests() {}

    @SuppressWarnings("removal")
    private static ServerPlayer playerNear(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        return player;
    }

    private static Villager adult(GameTestHelper helper, int x, int z, VillagerProfession profession) {
        Villager v = helper.spawn(EntityType.VILLAGER, new BlockPos(x, 1, z));
        v.setVillagerData(v.getVillagerData().setProfession(profession));
        v.setNoAi(true);
        return v;
    }

    private static ActiveQuest accept(GameTestHelper helper, ServerPlayer player, Villager giver, String quest) {
        QuestDefinition def = QuestDefinitions.get(QuestsModule.id(quest));
        helper.assertTrue(def != null, quest + " should be loaded");
        QuestManager.tryOffer(giver, def);
        QuestManager.answer(player, giver.getUUID(), QuestManager.ACCEPT);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        helper.assertTrue(quests.quests.size() == 1, "quest " + quest + " should start");
        return quests.quests.get(0);
    }

    /** "I can't do this after all": the quest fails (reason: gave up), the giver is a little disappointed. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void quitInDialogue(GameTestHelper helper) {
        Villager giver = adult(helper, 2, 2, VillagerProfession.FARMER);
        ServerPlayer player = playerNear(helper);
        ActiveQuest quest = accept(helper, player, giver, "errand/tired_soil");

        QuestDialogue.Opening open = QuestDialogue.open(player, giver);
        helper.assertTrue(open.options().contains("quest:quit"), "dialogue should offer to give up, got " + open.options());
        QuestDialogue.handle(player, giver, "quest:quit", List.of("smalltalk", "goodbye"));
        helper.assertTrue(quest.state == ActiveQuest.State.FAILED && quest.failReason.equals("villagequests.fail.quit"), "quest should fail as 'gave up'");
        helper.assertTrue(player.getData(QuestRegistries.GOODWILL).get(giver.getUUID()) < 0, "the giver is a little disappointed");
        helper.assertTrue(QuestManager.findNote(player, quest.instance) < 0, "note taken back");
        helper.succeed();
    }

    /** Giver dies: the quest stays in the journal, failed with a reason, until cleared with the X. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void giverDiesShowsInJournal(GameTestHelper helper) {
        Villager giver = adult(helper, 2, 2, VillagerProfession.FARMER);
        ServerPlayer player = playerNear(helper);
        ActiveQuest quest = accept(helper, player, giver, "errand/tired_soil");
        giver.kill();

        helper.assertTrue(quest.state == ActiveQuest.State.FAILED && quest.failReason.equals("villagequests.fail.giver_died"), "quest should fail: giver died");
        List<QuestNetwork.Entry> entries = QuestJournal.entries(player);
        helper.assertTrue(entries.size() == 1 && entries.get(0).failed(), "journal shows it as failed");
        QuestJournal.onAction(player, new QuestNetwork.QuestAction(quest.instance, QuestNetwork.QuestAction.DISMISS));
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "the X clears it");
        helper.succeed();
    }

    /** Giving up from the journal works without the villager around. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void quitFromJournal(GameTestHelper helper) {
        Villager giver = adult(helper, 2, 2, VillagerProfession.FARMER);
        ServerPlayer player = playerNear(helper);
        ActiveQuest quest = accept(helper, player, giver, "errand/tired_soil");
        QuestJournal.onAction(player, new QuestNetwork.QuestAction(quest.instance, QuestNetwork.QuestAction.QUIT));
        helper.assertTrue(quest.state == ActiveQuest.State.FAILED, "journal Give up fails the quest");
        helper.assertTrue(QuestDialogue.open(player, giver).options().stream().noneMatch(o -> o.equals("quest:quit")), "nothing left to quit");
        helper.succeed();
    }

    /** Someone else died: the quest fails "through nobody's fault", and the giver pays a little for your time. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void compensationWhenSomeoneElseDies(GameTestHelper helper) {
        Villager giver = adult(helper, 2, 2, VillagerProfession.FARMER);
        ServerPlayer player = playerNear(helper);
        ActiveQuest quest = accept(helper, player, giver, "errand/tired_soil");
        QuestManager.failCompensated(player, quest, "Kevin");
        helper.assertTrue(quest.compensationOwed(), "giver owes compensation");
        helper.assertTrue(QuestJournal.entries(player).get(0).failed(), "journal shows it as failed");

        QuestDialogue.Opening open = QuestDialogue.open(player, giver);
        helper.assertTrue(open.greeting().isPresent(), "giver opens with condolences");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 2, "a mediocre reward");
        helper.assertFalse(quest.compensationOwed(), "paid once");
        helper.assertTrue(quest.failReason.startsWith("villagequests.fail.recipient_died_paid"), "journal notes it was paid");
        helper.succeed();
    }

    /** A village with no golem asks for one; a player-built golem nearby completes it. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void buildGolem(GameTestHelper helper) {
        Villager giver = adult(helper, 2, 2, VillagerProfession.MASON);
        adult(helper, 0, 2, VillagerProfession.FARMER);
        adult(helper, 4, 2, VillagerProfession.LIBRARIAN);
        ServerPlayer player = playerNear(helper);
        ActiveQuest quest = accept(helper, player, giver, "build/golem");
        helper.assertTrue(quest.count == 1, "one golem needed");
        helper.assertFalse(QuestManager.talk(player, giver), "nothing to hand in yet");

        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(0, 1, 0));
        golem.setNoAi(true);
        golem.setPlayerCreated(true);
        helper.assertTrue(QuestManager.talk(player, giver), "the giver sees the new golem");
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "build quest done");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 10, "rewarded");
        helper.succeed();
    }
}

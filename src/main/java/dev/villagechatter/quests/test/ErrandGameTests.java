package dev.villagechatter.quests.test;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Errands (small item requests) and goodwill. Run with {@code ./gradlew runGameTestServer}. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ErrandGameTests {
    private ErrandGameTests() {}

    private record Setup(Villager kid, ServerPlayer player, PlayerQuests quests, ActiveQuest quest) {}

    @SuppressWarnings("removal")
    private static Setup acceptPoppyErrand(GameTestHelper helper) {
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        kid.setAge(-24000);
        kid.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("errand/golem_poppies"));
        helper.assertTrue(def != null && def.isErrand(), "golem_poppies errand should be loaded");
        QuestManager.tryOffer(kid, def);
        int asked = kid.getData(QuestRegistries.GIVER).count();
        helper.assertTrue(asked >= 10 && asked <= 16, "kid should ask for 10-16 poppies, asked " + asked);
        helper.assertTrue(QuestManager.talk(player, kid), "baby villager with an errand should talk");
        QuestManager.answer(player, kid.getUUID(), QuestManager.ACCEPT);

        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        helper.assertTrue(quests.quests.size() == 1, "accepting should start one quest");
        ActiveQuest quest = quests.quests.get(0);
        helper.assertTrue(quest.count == asked, "quest should remember the amount");
        helper.assertTrue(QuestManager.findNote(player, quest.instance) < 0, "no paper note: the journal tracks it");
        return new Setup(kid, player, quests, quest);
    }

    /** Bring the poppies: items taken, emeralds + XP paid, goodwill credited, golems get their flowers. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void errandDelivered(GameTestHelper helper) {
        Setup s = acceptPoppyErrand(helper);
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(0, 1, 0));
        golem.setNoAi(true);

        s.player.getInventory().add(new ItemStack(Items.POPPY, s.quest.count + 3));
        helper.assertTrue(QuestManager.talk(s.player, s.kid), "kid should notice the poppies");
        QuestManager.answer(s.player, s.kid.getUUID(), QuestManager.DELIVER);

        helper.assertTrue(s.quests.quests.isEmpty(), "errand should be finished");
        helper.assertTrue(s.player.getInventory().countItem(Items.POPPY) == 3, "exactly the asked amount should be taken");
        helper.assertTrue(s.player.getInventory().countItem(Items.EMERALD) >= 1, "should be paid at least one emerald");
        helper.assertTrue(s.player.totalExperience > 0, "should get a little XP");
        helper.assertTrue(QuestManager.findNote(s.player, s.quest.instance) < 0, "note should be taken back");
        double goodwill = s.player.getData(QuestRegistries.GOODWILL).get(s.kid.getUUID());
        helper.assertTrue(Math.abs(goodwill - 1.0) < 1e-6, "kid should hold 1.0 goodwill, has " + goodwill);
        helper.assertTrue(golem.getOfferFlowerTick() > 0, "golems should be offered the flowers");
        helper.succeed();
    }

    /** Too few items: nothing is taken, the errand stays open. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void errandShort(GameTestHelper helper) {
        Setup s = acceptPoppyErrand(helper);
        s.player.getInventory().add(new ItemStack(Items.POPPY, s.quest.count - 1));
        helper.assertFalse(QuestManager.talk(s.player, s.kid), "no hand-in prompt without enough poppies");
        QuestManager.answer(s.player, s.kid.getUUID(), QuestManager.DELIVER);
        helper.assertTrue(s.quests.quests.size() == 1, "errand should stay open");
        helper.assertTrue(s.player.getInventory().countItem(Items.POPPY) == s.quest.count - 1, "nothing should be taken");
        helper.succeed();
    }

    /** Missing an errand's deadline costs nothing: the quest just goes away. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void errandLapses(GameTestHelper helper) {
        Setup s = acceptPoppyErrand(helper);
        s.quest.deadline = helper.getLevel().getGameTime() - 1;
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(s.quest.state == dev.villagechatter.quests.quest.ActiveQuest.State.FAILED
                    && s.quest.failReason.equals("villagequests.fail.lapsed"), "lapsed errand should show as failed (ran out of time)");
            helper.assertTrue(s.kid.getPlayerReputation(s.player) == 0, "no reputation change for a lapsed errand");
            helper.succeed();
        });
    }

    /** Goodwill collects in fractions and pays out as real gossip once it reaches 2. */
    @GameTest(template = "empty", timeoutTicks = 20)
    @SuppressWarnings("removal")
    public static void goodwillPaysOut(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        Reputation.addGoodwill(player, villager, 1.5, 0);
        helper.assertTrue(villager.getPlayerReputation(player) == 0, "1.5 goodwill shouldn't pay out yet");
        Reputation.addGoodwill(player, villager, 1.0, 0);
        helper.assertTrue(villager.getPlayerReputation(player) == 2, "2.5 goodwill should pay out 2 reputation, got "
                + villager.getPlayerReputation(player));
        double left = player.getData(QuestRegistries.GOODWILL).get(villager.getUUID());
        helper.assertTrue(Math.abs(left - 0.5) < 1e-6, "0.5 goodwill should be left over, has " + left);
        helper.succeed();
    }
}

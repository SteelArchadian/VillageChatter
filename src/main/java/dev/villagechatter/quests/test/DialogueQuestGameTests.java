package dev.villagechatter.quests.test;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestDialogue;
import dev.villagechatter.quests.quest.QuestManager;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Quests through Village Chatter's dialogue box (QuestDialogue), the way players meet them. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class DialogueQuestGameTests {
    private DialogueQuestGameTests() {}

    /** Kid asks for poppies in the dialogue, you accept, come back with them, hand them in: all as dialogue options. */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void errandThroughDialogue(GameTestHelper helper) {
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        kid.setAge(-24000);
        kid.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("errand/golem_poppies"));
        QuestManager.tryOffer(kid, def);
        List<String> base = List.of("kid_smalltalk", "goodbye");
        helper.assertTrue(ConversationManager.villagerOptions(kid).contains("kid_smalltalk"), "kid keeps its small talk");

        QuestDialogue.Opening open = QuestDialogue.open(player, kid);
        helper.assertTrue(open.options().contains("quest:ask"), "dialogue should offer to hear the request, got " + open.options());
        helper.assertTrue(open.greeting().isPresent(), "a kid with a request greets you with it");
        helper.assertTrue(open.labels().containsKey("quest:ask"), "quest options carry their own labels");

        QuestDialogue.Reply story = QuestDialogue.handle(player, kid, "quest:ask", base);
        helper.assertTrue(story != null && story.options().contains("quest:accept"), "story should offer accept");
        QuestDialogue.Reply accepted = QuestDialogue.handle(player, kid, "quest:accept", base);
        helper.assertTrue(accepted != null, "accepting should answer");
        helper.assertTrue(accepted.options().contains("kid_smalltalk"), "back to the usual options after accepting");
        int count = player.getData(QuestRegistries.PLAYER_QUESTS).quests.get(0).count;

        player.getInventory().add(new ItemStack(Items.POPPY, count));
        QuestDialogue.Opening ready = QuestDialogue.open(player, kid);
        helper.assertTrue(ready.options().contains("quest:deliver"), "should offer to hand the poppies in, got " + ready.options());
        QuestDialogue.handle(player, kid, "quest:deliver", base);
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "errand should be done");
        helper.assertTrue(player.getInventory().countItem(Items.POPPY) == 0, "poppies handed over");
        helper.succeed();
    }

    /** Jobless villager: ask, accept, they get a job, and the next conversation opens with their thank-you. */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void jobThroughDialogue(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        villager.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        QuestManager.tryOffer(villager, QuestDefinitions.get(QuestsModule.id("job_seeker")));
        List<String> base = List.of("smalltalk", "goodbye");

        QuestDialogue.handle(player, villager, "quest:ask", base);
        QuestDialogue.handle(player, villager, "quest:accept", base);
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.size() == 1, "job hunt started");

        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        QuestDialogue.Opening thanks = QuestDialogue.open(player, villager);
        helper.assertTrue(thanks.greeting().isPresent(), "employed villager opens with a thank-you");
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "job quest done");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 2, "rewarded");
        helper.succeed();
    }
}

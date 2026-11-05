package dev.villagechatter.quests.test;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.GuardQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestDialogue;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Guard requests. Guard Villagers isn't loaded in tests, so a zombie with no AI stands in for the guard
 * (GuardQuests' offer/talk/hand-in don't care what kind of mob it is; only the scan and dialogue do).
 */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class GuardQuestGameTests {
    private GuardQuestGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("removal")
    public static void guardSwordRequest(GameTestHelper helper) {
        Zombie guard = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        guard.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        List<String> base = List.of("smalltalk", "goodbye");

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("guard/new_sword"));
        helper.assertTrue(def != null && def.givers() == QuestDefinition.Givers.GUARD, "guard/new_sword should be a guard request");
        helper.assertFalse(def.givers().allows(false), "villagers never get guard requests");
        GuardQuests.tryOffer(guard, def);

        QuestDialogue.Opening open = GuardQuests.open(player, guard);
        helper.assertTrue(open.options().contains("quest:guard_ask") && open.greeting().isPresent(), "the guard has something to ask");
        QuestDialogue.Reply story = GuardQuests.handle(player, guard, "quest:guard_ask", base);
        helper.assertTrue(story != null && story.options().contains("quest:accept"), "story offers to accept");
        GuardQuests.handle(player, guard, "quest:accept", base);
        ActiveQuest quest = player.getData(QuestRegistries.PLAYER_QUESTS).quests.get(0);
        helper.assertTrue(quest.isActive() && quest.giver.equals(guard.getUUID()), "guard quest started");

        player.getInventory().add(new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(GuardQuests.open(player, guard).options().contains("quest:deliver"), "the guard sees the sword");
        GuardQuests.handle(player, guard, "quest:deliver", base);
        helper.assertTrue(player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "request done");
        helper.assertTrue(player.getInventory().countItem(Items.IRON_SWORD) == 0, "sword handed over");
        double trust = player.getData(QuestRegistries.GOODWILL).get(guard.getUUID());
        helper.assertTrue(trust >= 9 - 1e-6, "the guard's trust goes up a lot (goodwill keyed to them), got " + trust);
        helper.succeed();
    }
}

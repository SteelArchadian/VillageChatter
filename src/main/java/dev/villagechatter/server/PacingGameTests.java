package dev.villagechatter.server;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One villager gives a few fresh answers, then gets tired of questions, then repeats themselves. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PacingGameTests {
    private PacingGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    @SuppressWarnings("removal")
    public static void smallTalkPacing(GameTestHelper helper) {
        Villager a = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        Villager b = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String[] expected = {"smalltalk", "smalltalk", "smalltalk", "tired", "repeat", "repeat"};
        for (int i = 0; i < expected.length; i++) {
            String topic = ConversationManager.smallTalkTopic(player, a);
            helper.assertTrue(topic.equals(expected[i]), "question " + (i + 1) + " should be " + expected[i] + ", was " + topic);
        }
        helper.assertTrue(ConversationManager.smallTalkTopic(player, b).equals("smalltalk"), "another villager answers fresh");
        helper.succeed();
    }
}

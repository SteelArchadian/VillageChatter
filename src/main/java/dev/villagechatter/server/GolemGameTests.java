package dev.villagechatter.server;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Golem kindness: a little village goodwill and XP, with a cooldown so it can't be farmed. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class GolemGameTests {
    private GolemGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("removal")
    public static void golemKindness(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(1, 1, 1));
        golem.setNoAi(true);
        golem.setHealth(40.0F);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        villager.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().add(new ItemStack(Items.POPPY, 4));
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 4));

        GolemTalk.handle(player, golem, "give_poppy");
        double afterPoppy = standing(player, villager);
        helper.assertTrue(afterPoppy > 0, "a poppy should raise the village's opinion of you, got " + afterPoppy);
        helper.assertTrue(player.totalExperience > 0, "and a sliver of XP");
        int xp = player.totalExperience;

        GolemTalk.handle(player, golem, "give_poppy");
        helper.assertTrue(Math.abs(standing(player, villager) - afterPoppy) < 1e-6, "a second poppy right away shouldn't count again");
        helper.assertTrue(player.totalExperience == xp, "no XP during the cooldown");

        float before = golem.getHealth();
        GolemTalk.handle(player, golem, "give_iron");
        helper.assertTrue(golem.getHealth() > before, "iron should repair the golem");
        helper.assertTrue(standing(player, villager) > afterPoppy, "a repair should raise it again (separate cooldown)");
        helper.succeed();
    }

    /** Paid-out gossip plus goodwill still waiting to be paid out. */
    private static double standing(ServerPlayer player, Villager villager) {
        return villager.getPlayerReputation(player) + player.getData(QuestRegistries.GOODWILL).get(villager.getUUID());
    }
}

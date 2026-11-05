package dev.villagechatter.reputation;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Standing titles for villagers and bells. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class StandingGameTests {
    private StandingGameTests() {}

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    @SuppressWarnings("removal")
    public static void standingTitles(GameTestHelper helper) {
        Villager a = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        Villager b = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        a.setNoAi(true);
        b.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        helper.assertTrue(key(Standing.forVillager(player, a).title()).endsWith("villager.stranger"), "unknown villager: stranger");

        a.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, 25);
        helper.assertTrue(key(Standing.forVillager(player, a).title()).endsWith("villager.friend"), "+25: friend, got "
                + key(Standing.forVillager(player, a).title()));
        b.getGossips().add(player.getUUID(), GossipType.MINOR_NEGATIVE, 30);
        helper.assertTrue(key(Standing.forVillager(player, b).title()).endsWith("villager.distrusted"), "-30: distrusted");

        BlockPos bell = helper.absolutePos(new BlockPos(2, 1, 0));
        helper.setBlock(new BlockPos(2, 1, 0), Blocks.BELL);
        // Their bell is their meeting point, as in a real village: other tests' bells nearby link into one town, and
        // only villagers who meet at one of its bells count.
        var meet = net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), bell);
        a.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT, meet);
        b.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT, meet);
        Standing.View village = Standing.forBell(player, helper.getLevel(), bell);
        helper.assertTrue(village != null, "a bell with villagers around is a village bell");
        // Average of +25 and -30 is -2.5: known around here.
        helper.assertTrue(key(village.title()).endsWith("village.neutral"), "village average -2.5: neutral, got " + key(village.title()));

        helper.setBlock(new BlockPos(2, 1, 0), Blocks.AIR);
        helper.assertTrue(Standing.forBell(player, helper.getLevel(), bell) == null, "no bell, no readout");
        helper.succeed();
    }
}

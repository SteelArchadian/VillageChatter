package dev.villagechatter.life;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Piglins out of the Nether (PiglinNature): a day's grace, netherrack resets it, and zombified ones can be cured. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PiglinGameTests {
    private PiglinGameTests() {}

    /** Past vanilla's 15 seconds an Overworld piglin is still a piglin; any Nether ground winds its timer back. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void piglinHoldsOut(GameTestHelper helper) {
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(2, 2, 2));
        piglin.setNoAi(true);
        helper.runAfterDelay(340, () -> {
            helper.assertTrue(piglin.isAlive() && !piglin.isRemoved(), "still a piglin after vanilla's 300 ticks");
            helper.assertTrue(PiglinNature.ticksAway(piglin) >= 300, "the timer runs out of the Nether");
            helper.setBlock(new BlockPos(2, 1, 2), Blocks.CRIMSON_NYLIUM); // any Nether ground, under its feet (no AI, so it floats where spawned)
        });
        helper.runAfterDelay(380, () -> {
            helper.assertTrue(PiglinNature.ticksAway(piglin) == 0, "Nether ground resets the timer");
            helper.succeed();
        });
    }

    /** With a quarter of its time left, a piglin walks to Nether ground nearby and is refreshed. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void piglinSeeksGround(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.SOUL_SOIL);
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(0, 1, 0));
        int max = dev.villagechatter.ChatterConfig.PIGLIN_ZOMBIFY_TICKS.get();
        piglin.setData(dev.villagechatter.ChatterAttachments.PIGLIN_NATURE, java.util.List.of(max - max / 10, 1));
        helper.succeedWhen(() -> helper.assertTrue(PiglinNature.ticksAway(piglin) == 0, "walked to the soul soil"));
    }

    /** Weakness and a golden apple: after the wait, a zombified piglin is a piglin again. */
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void zombifiedPiglinCures(GameTestHelper helper) {
        var zombie = helper.spawn(EntityType.ZOMBIFIED_PIGLIN, new BlockPos(2, 2, 2));
        zombie.setNoAi(true);
        PiglinNature.startCure(zombie, null, 40);
        helper.assertTrue(PiglinNature.curing(zombie), "the cure starts");
        helper.runAfterDelay(80, () -> {
            helper.assertEntityPresent(EntityType.PIGLIN);
            helper.assertEntityNotPresent(EntityType.ZOMBIFIED_PIGLIN);
            helper.succeed();
        });
    }
}

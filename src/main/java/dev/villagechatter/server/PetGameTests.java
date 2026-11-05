package dev.villagechatter.server;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pet dialogue: stay / follow always matches what you see, and tricks never leave a pet stuck sitting. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PetGameTests {
    private PetGameTests() {}

    @SuppressWarnings("removal")
    private static Cat tamedCat(GameTestHelper helper, ServerPlayer owner) {
        Cat cat = helper.spawn(EntityType.CAT, new BlockPos(2, 1, 2));
        cat.tame(owner);
        return cat;
    }

    /** "Stay here" -> the cat sits, and the next option is "Come on, let's go". */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void stayThenFollow(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Cat cat = tamedCat(helper, player);
        helper.assertTrue(PetTalk.options(cat).contains("stay"), "a standing cat should offer 'stay'");
        PetTalk.handle(player, cat, "stay");
        helper.assertTrue(PetTalk.isSitting(cat), "the cat should sit");
        helper.assertTrue(PetTalk.options(cat).contains("follow"), "a sitting cat should offer 'follow'");
        PetTalk.handle(player, cat, "follow");
        helper.assertFalse(cat.isOrderedToSit() || cat.isInSittingPose(), "the cat should stand up");
        helper.assertTrue(PetTalk.options(cat).contains("stay"), "and offer 'stay' again");
        helper.succeed();
    }

    /** Pose and flag out of step (the butt-scoot): talking to the pet puts them back in agreement. */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void sitStateRepaired(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Cat cat = tamedCat(helper, player);
        cat.setInSittingPose(true);
        cat.setOrderedToSit(false);
        PetTalk.flags(cat);
        helper.assertTrue(cat.isOrderedToSit() && cat.isInSittingPose(), "a cat that looks seated should be seated");
        helper.assertTrue(PetTalk.options(cat).contains("follow"), "and offer 'follow'");
        helper.succeed();
    }

    /** A trick from standing ends standing (free to follow), never sitting. */
    @GameTest(template = "empty", timeoutTicks = 140)
    @SuppressWarnings("removal")
    public static void trickEndsStanding(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Cat cat = tamedCat(helper, player);
        PetTalk.handle(player, cat, "trick");
        helper.assertTrue(PetTalk.isDoingTrick(cat), "the cat should be doing a trick");
        helper.runAfterDelay(100, () -> {
            helper.assertFalse(PetTalk.isDoingTrick(cat), "the trick should be over");
            helper.assertFalse(cat.isOrderedToSit() || cat.isInSittingPose(), "the cat should be standing");
            helper.succeed();
        });
    }
}

package dev.villagechatter.server;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.villagechatter.reputation.faction.FactionBook;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Piglins: talking, the gold barter by dialogue, the vanilla barter left alone, and the piglin meter. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PiglinGameTests {
    private PiglinGameTests() {}

    private static Piglin piglin(GameTestHelper helper, boolean ai) {
        Piglin p = helper.spawn(EntityType.PIGLIN, new BlockPos(2, 1, 2));
        p.setImmuneToZombification(true);
        p.setNoAi(!ai);
        p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return p;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "goldbringer"));
        BlockPos at = helper.absolutePos(new BlockPos(4, 1, 2));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        return player;
    }

    /** Empty-handed you talk; gold in hand is the vanilla barter; an angry piglin won't talk. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void piglinTalkGate(GameTestHelper helper) {
        Piglin piglin = piglin(helper, false);
        ServerPlayer player = player(helper);
        helper.assertTrue(ConversationManager.canStartConversation(player, piglin), "empty-handed: talk");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_INGOT));
        helper.assertFalse(ConversationManager.canStartConversation(player, piglin), "gold in hand: the vanilla barter");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        piglin.getBrain().setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, player.getUUID(), 600L);
        helper.assertFalse(ConversationManager.canStartConversation(player, piglin), "angry: no talk");
        helper.succeed();
    }

    /** One vanilla barter roll per ingot, the gold taken, standing up a little. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void piglinBarterCount(GameTestHelper helper) {
        Piglin piglin = piglin(helper, false);
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        double before = FactionBook.piglin(player);
        var loot = PiglinTalk.barter(player, piglin, 3);
        helper.assertTrue(PiglinTalk.gold(player) == 2, "three ingots taken");
        helper.assertTrue(loot.size() >= 3, "a roll per ingot, got " + loot.size());
        helper.assertTrue(FactionBook.piglin(player) > before, "piglins like a fair trade");
        helper.succeed();
    }

    /** The dialogue barter: a moment to look, then the goods land at your feet. */
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void piglinDialogueBarter(GameTestHelper helper) {
        Piglin piglin = piglin(helper, false);
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 4));
        AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(8);
        int dropsBefore = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
        helper.assertTrue(PiglinTalk.handle(player, piglin, PiglinTalk.AMOUNT + 2), "the amount is a piglin action");
        helper.assertTrue(PiglinTalk.gold(player) == 2, "two ingots handed over");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size() == dropsBefore, "still looking it over");
        helper.runAfterDelay(dev.villagechatter.ChatterConfig.PIGLIN_ADMIRE_TICKS.get() + 5, () -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size() >= dropsBefore + 2, "the goods are thrown");
            helper.succeed();
        });
    }

    /** Dropping gold the vanilla way still works: the piglin picks it up and admires it. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void piglinVanillaBarterUntouched(GameTestHelper helper) {
        Piglin piglin = piglin(helper, true);
        helper.spawnItem(Items.GOLD_INGOT, 3.5F, 1.5F, 2.5F);
        helper.succeedWhen(() -> helper.assertTrue(piglin.getOffhandItem().is(Items.GOLD_INGOT), "picked up the gold"));
    }

    /** Killing a piglin in front of two others: once one tells the other, it costs standing. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void piglinStandingFalls(GameTestHelper helper) {
        Piglin victim = piglin(helper, false);
        Piglin a = helper.spawn(EntityType.PIGLIN, new BlockPos(1, 1, 3));
        Piglin b = helper.spawn(EntityType.PIGLIN, new BlockPos(3, 1, 3));
        for (Piglin p : new Piglin[] {a, b}) {
            p.setImmuneToZombification(true);
            p.setNoAi(true);
        }
        ServerPlayer player = player(helper);
        double before = FactionBook.piglin(player);
        victim.hurt(helper.getLevel().damageSources().playerAttack(player), 1000F);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(FactionBook.piglin(player) < before, "word got around: it costs standing");
            helper.succeed();
        });
    }
}

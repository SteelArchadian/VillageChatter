package dev.villagechatter.life;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The small scenes: a villager asks for gear you dropped; a neighbour wants an apology for a hit. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WorldEventsGameTests {
    private WorldEventsGameTests() {}

    /** A fake player: scripted conversations send packets, which a mock player's connection refuses. */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "passerby"));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** Dropped a helmet near a villager: they ask, you say yes, they wear it. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void droppedGearGiven(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        villager.setNoAi(true);
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        ItemEntity item = new ItemEntity(helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(Items.IRON_HELMET));
        helper.getLevel().addFreshEntity(item);
        WorldEvents.onToss(new ItemTossEvent(item, player));
        helper.runAfterDelay(80, () -> {
            WorldEvents.check(player);
            helper.assertTrue(WorldEvents.handle(player, villager, WorldEvents.GIVE), "the villager should be asking for it");
            helper.assertTrue(villager.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "and wear it after a yes");
            helper.assertFalse(item.isAlive(), "the dropped one is gone");
            helper.succeed();
        });
    }

    /** Refuse twice and they swing at you; it takes two refusals. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void kinWantsApology(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Villager kin = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        helper.assertFalse(WorldEvents.handle(player, kin, WorldEvents.APOLOGISE), "nothing to apologise for yet");
        WorldEvents.confront(player, kin);
        helper.assertTrue(WorldEvents.handle(player, kin, WorldEvents.REFUSE), "first refusal: they insist");
        helper.assertFalse(kin.swinging, "no swing yet");
        helper.assertTrue(WorldEvents.handle(player, kin, WorldEvents.REFUSE), "second refusal");
        helper.assertTrue(kin.swinging, "they swing at you");
        helper.assertFalse(WorldEvents.handle(player, kin, WorldEvents.REFUSE), "and the scene is over");
        helper.succeed();
    }
}

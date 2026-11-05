package dev.villagechatter.quests.test;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Lost buddy: map, captive appears when you're near, talk frees them, the emerald block leads them home. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RescueGameTests {
    private RescueGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 600)
    @SuppressWarnings("removal")
    public static void buddyRescued(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager giver = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        giver.setVillagerData(giver.getVillagerData().setProfession(VillagerProfession.FARMER));
        giver.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 home = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(home.x, home.y, home.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("lost_buddy"));
        helper.assertTrue(def != null && def.isRescue(), "lost_buddy should be loaded");
        QuestManager.tryOffer(giver, def);
        helper.assertTrue(QuestManager.talk(player, giver), "giver should ask for help");
        QuestManager.answer(player, giver.getUUID(), QuestManager.ACCEPT);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        helper.assertTrue(quests.quests.size() == 1, "rescue should start");
        ActiveQuest quest = quests.quests.get(0);
        helper.assertTrue(player.getInventory().countItem(Items.FILLED_MAP) == 0, "no map: the journal has the rough spot");
        helper.assertTrue(QuestManager.findNote(player, quest.instance) < 0, "and no note");

        BlockPos target = quest.target;
        level.setChunkForced(target.getX() >> 4, target.getZ() >> 4, true);
        player.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 6.5);
        player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK));

        // The captive appears once the forced chunk is entity-ticking, which takes longer on a busy server: wait for it,
        // then a little more so a second one would show up too.
        AABB area = new AABB(target).inflate(16);
        java.util.function.Supplier<List<Villager>> captives = () -> level.getEntitiesOfClass(Villager.class, area,
                v -> v.getData(QuestRegistries.CAPTIVE).instance().equals(quest.instance));
        awaitThen(helper, 400, () -> !captives.get().isEmpty(), () -> helper.runAfterDelay(45, () -> {
            List<Villager> buddies = captives.get();
            helper.assertTrue(buddies.size() == 1, "one captive should appear near the owner, got " + buddies.size());
            Villager buddy = buddies.get(0);
            helper.assertTrue(buddy.isInvulnerable(), "captive can't be hurt");
            helper.assertTrue(!level.getEntitiesOfClass(Pillager.class, area).isEmpty(), "captors keep watch out in the wilds");

            helper.assertTrue(QuestManager.talk(player, buddy), "talking to the captive frees them");
            helper.assertTrue(quest.itemDropped, "quest should know they're free");

            // Skip the walk: put the buddy and the player back at the village.
            level.setChunkForced(target.getX() >> 4, target.getZ() >> 4, false);
            buddy.moveTo(home.x + 2, home.y, home.z);
            player.moveTo(home.x, home.y, home.z);
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(quests.quests.isEmpty(), "reunion should finish the quest");
                helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 10, "rewarded");
                helper.assertFalse(buddy.isInvulnerable(), "buddy is an ordinary villager again");
                helper.assertFalse(buddy.hasData(QuestRegistries.CAPTIVE), "no longer a captive");
                helper.assertTrue(giver.getPlayerReputation(player) > 0, "the giver is grateful");
                helper.succeed();
            });
        }));
    }

    /** Runs then once ready holds (checked every 5 ticks), or fails after ticks. */
    private static void awaitThen(GameTestHelper helper, int ticks, java.util.function.BooleanSupplier ready, Runnable then) {
        if (ready.getAsBoolean()) then.run();
        else if (ticks <= 0) helper.fail("one captive should appear near the owner, got 0");
        else helper.runAfterDelay(5, () -> awaitThen(helper, ticks - 5, ready, then));
    }
}

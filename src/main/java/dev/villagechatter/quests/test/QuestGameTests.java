package dev.villagechatter.quests.test;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.GuardianManager;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * End-to-end checks of the heirloom quest. Run with {@code ./gradlew runGameTestServer}
 * (only enabled for the villagequests namespace in dev runs; never active in normal play).
 */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private QuestGameTests() {}

    @SuppressWarnings("removal")
    private static Setup setup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.WEAPONSMITH));
        villager.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 home = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(home.x, home.y, home.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("family_sword"));
        helper.assertTrue(def != null, "family_sword quest should be loaded");
        QuestManager.tryOffer(villager, def);
        helper.assertTrue(QuestManager.talk(player, villager), "villager with an offer should talk");
        QuestManager.answer(player, villager.getUUID(), QuestManager.ACCEPT);

        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        helper.assertTrue(quests.quests.size() == 1, "accepting should start one quest");
        ActiveQuest quest = quests.quests.get(0);
        helper.assertTrue(QuestManager.findNote(player, quest.instance) < 0, "no paper note: the journal tracks it");
        helper.assertFalse(villager.getData(QuestRegistries.GIVER).hasOffer(), "offer should be used up");
        double dist = Math.sqrt(quest.target.distSqr(new BlockPos(villager.getBlockX(), quest.target.getY(), villager.getBlockZ())));
        helper.assertTrue(dist >= 48, "target should be at least 48 blocks away, was " + dist);
        QuestsModule.LOGGER.info("[test] target {} ({}) {} blocks away", quest.target, quest.kind, (int) dist);
        return new Setup(level, villager, player, home, quests, quest);
    }

    private record Setup(ServerLevel level, Villager villager, ServerPlayer player, Vec3 home, PlayerQuests quests, ActiveQuest quest) {}

    /** Recover the heirloom from the guardian and fetch the reward. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void heirloomReturned(GameTestHelper helper) {
        Setup s = setup(helper);
        killGuardian(helper, s, heirloom -> {
            s.player.moveTo(s.home.x, s.home.y, s.home.z);
            s.player.getInventory().add(heirloom);
            helper.assertTrue(QuestManager.talk(s.player, s.villager), "giver should notice the heirloom");
            QuestManager.answer(s.player, s.villager.getUUID(), QuestManager.RETURN);

            helper.assertTrue(s.quests.quests.isEmpty(), "quest should be finished");
            helper.assertTrue(QuestManager.findNote(s.player, s.quest.instance) < 0, "note should be taken back");
            helper.assertTrue(s.player.getInventory().countItem(Items.EMERALD) >= 6, "should be paid in emeralds");
            helper.assertTrue(s.player.getInventory().countItem(Items.IRON_SWORD) == 0, "sword should be handed back");
            helper.assertTrue(s.villager.getPlayerReputation(s.player) > 0, "reputation should go up");
            helper.succeed();
        });
    }

    /** Keep the heirloom by lying: the sword stays, reputation drops. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void heirloomKept(GameTestHelper helper) {
        Setup s = setup(helper);
        killGuardian(helper, s, heirloom -> {
            s.player.moveTo(s.home.x, s.home.y, s.home.z);
            s.player.getInventory().add(heirloom);
            QuestManager.answer(s.player, s.villager.getUUID(), QuestManager.KEEP);

            helper.assertTrue(s.quests.quests.isEmpty(), "quest should be finished");
            helper.assertTrue(s.player.getInventory().countItem(Items.IRON_SWORD) == 1, "sword should stay with the player");
            boolean stillTagged = s.player.getInventory().items.stream()
                    .anyMatch(stack -> stack.is(Items.IRON_SWORD) && stack.has(QuestRegistries.HEIRLOOM.get()));
            helper.assertFalse(stillTagged, "kept sword should no longer count as a quest item");
            helper.assertTrue(s.villager.getPlayerReputation(s.player) < 0, "reputation should go down");
            helper.succeed();
        });
    }

    /** Miss the deadline: the guardian no longer drops anything, and the giver holds a grudge. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deadlineMissed(GameTestHelper helper) {
        Setup s = setup(helper);
        s.quest.deadline = s.level.getGameTime() - 1;
        // Let the once-a-second quest tick run.
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(s.quest.state == ActiveQuest.State.FAILED && s.quest.failReason.equals("villagequests.fail.expired"),
                    "missed deadline: grudge settled with the giver nearby, quest shows as failed, was " + s.quest.state);
            helper.assertTrue(s.villager.getPlayerReputation(s.player) < 0, "reputation should go down");
            helper.succeed();
        });
    }

    /** Walks the player to the target, lets the normal quest tick spawn the guardian, kills it, hands over the drop. */
    private static void killGuardian(GameTestHelper helper, Setup s, java.util.function.Consumer<ItemStack> then) {
        BlockPos target = s.quest.target;
        s.level.setChunkForced(target.getX() >> 4, target.getZ() >> 4, true);
        s.player.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 4.5);

        helper.runAfterDelay(45, () -> {
            AABB area = new AABB(target).inflate(16);
            List<Mob> guardians = s.level.getEntitiesOfClass(Mob.class, area, m -> m.hasData(QuestRegistries.GUARDIAN));
            helper.assertTrue(guardians.size() == 1, "one guardian should spawn near the owner, got " + guardians.size());
            Mob guardian = guardians.get(0);
            helper.assertTrue(guardian.getMainHandItem().is(Items.IRON_SWORD), "guardian should hold the sword");
            helper.assertTrue(guardian.hasCustomName(), "guardian should be named");
            guardian.kill();

            helper.assertTrue(s.quest.itemDropped, "killing the guardian should drop the heirloom");
            List<ItemEntity> drops = s.level.getEntitiesOfClass(ItemEntity.class, area,
                    e -> e.getItem().has(QuestRegistries.HEIRLOOM.get()));
            helper.assertTrue(drops.size() == 1, "exactly one heirloom should drop, got " + drops.size());
            helper.assertTrue(drops.get(0).getItem().isEnchanted(), "heirloom should keep its enchantments");
            ItemStack heirloom = drops.get(0).getItem().copy();
            drops.get(0).discard();
            s.level.setChunkForced(target.getX() >> 4, target.getZ() >> 4, false);
            then.accept(heirloom);
        });
    }
}

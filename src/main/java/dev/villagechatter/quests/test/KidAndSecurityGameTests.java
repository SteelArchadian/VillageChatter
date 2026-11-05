package dev.villagechatter.quests.test;

import java.util.List;
import java.util.UUID;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.GuardQuests;
import dev.villagechatter.quests.quest.GuardianManager;
import dev.villagechatter.quests.quest.LostPet;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestDialogue;
import dev.villagechatter.quests.quest.Security;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A kid's lost pet, and a guard's security request (defensive blocks). */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class KidAndSecurityGameTests {
    private KidAndSecurityGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    @SuppressWarnings("removal")
    public static void lostPetComesHome(GameTestHelper helper) {
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        kid.setAge(-24000);
        kid.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("lost_pet"));
        helper.assertTrue(def != null && def.isLostPet() && def.givers().allows(true) && !def.givers().allows(false), "lost_pet is a kid's quest");
        long now = helper.getLevel().getGameTime();
        // The pet's spot is right here, so the test needn't travel.
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), kid.getUUID(), kid.getName().copy(), helper.getLevel().dimension(),
                kid.blockPosition(), helper.absolutePos(new BlockPos(6, 1, 6)), "forest", now, now + def.deadlineTicks(),
                ActiveQuest.State.ACTIVE, false, 0, 0);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        quests.quests.add(quest);

        // it only appears once its spot is ticking, which can take a few ticks in a fresh test area
        helper.startSequence().thenWaitUntil(() -> {
            GuardianManager.tickPlayer(player, quests, helper.getLevel().getGameTime());
            helper.assertTrue(quest.buddy != null && helper.getLevel().getEntity(quest.buddy) instanceof Mob, "the pet appears when you're close");
        }).thenExecute(() -> {
            Mob pet = (Mob) helper.getLevel().getEntity(quest.buddy);
            helper.assertTrue(pet.isInvulnerable(), "the lost pet can't be hurt");

            PlayerInteractEvent.EntityInteract click = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, pet);
            LostPet.onInteract(click);
            helper.assertTrue(click.isCanceled() && quest.itemDropped, "right-click: found");
            helper.assertTrue(pet instanceof net.minecraft.world.entity.TamableAnimal t && t.isOwnedBy(player), "it trusts you for the trip (pet talk works)");
            helper.assertTrue(LostPet.homeFor(pet) != null && LostPet.homeFor(pet).pos().equals(kid.blockPosition().below()), "'go home' means back to the kid");

            pet.moveTo(kid.getX() + 1, kid.getY(), kid.getZ());
            int xp = player.totalExperience;
            GuardianManager.tickPlayer(player, quests, helper.getLevel().getGameTime());
            helper.assertTrue(quests.quests.isEmpty(), "home with the kid: quest done");
            helper.assertTrue(player.totalExperience > xp, "XP reward");
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0, "no emeralds from a kid");
            helper.assertTrue(kid.getUUID().toString().equals(pet.getPersistentData().getString(LostPet.KID_OWNER)), "pet tagged with its kid");
            helper.assertFalse(pet.hasData(QuestRegistries.CAPTIVE) && !pet.getData(QuestRegistries.CAPTIVE).isNone(), "an ordinary animal again");
            helper.assertFalse(pet.isInvulnerable(), "no longer invulnerable");
            helper.assertFalse(((net.minecraft.world.entity.TamableAnimal) pet).isTame(), "the kid's again, not yours");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    @SuppressWarnings("removal")
    public static void keepingTheLostPet(GameTestHelper helper) {
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        kid.setAge(-24000);
        kid.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("lost_pet"));
        long now = helper.getLevel().getGameTime();
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), kid.getUUID(), kid.getName().copy(), helper.getLevel().dimension(),
                kid.blockPosition(), helper.absolutePos(new BlockPos(6, 1, 6)), "forest", now, now + def.deadlineTicks(),
                ActiveQuest.State.ACTIVE, false, 0, 0);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        quests.quests.add(quest);
        // the pet only appears once its spot is ticking, which can take a few ticks in a fresh test area
        helper.startSequence().thenWaitUntil(() -> {
            GuardianManager.tickPlayer(player, quests, helper.getLevel().getGameTime());
            helper.assertTrue(quest.buddy != null && helper.getLevel().getEntity(quest.buddy) != null, "the pet is out there");
        }).thenExecute(() -> {
            Mob pet = (Mob) helper.getLevel().getEntity(quest.buddy);
            LostPet.onInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, pet));

            int before = kid.getPlayerReputation(player);
            helper.assertTrue(LostPet.keep(player, pet), "\"I think I'll keep you for myself\"");
            helper.assertTrue(quest.state == ActiveQuest.State.FAILED, "the quest is over");
            helper.assertTrue(pet instanceof net.minecraft.world.entity.TamableAnimal t && t.isOwnedBy(player), "abandoned after finding it: it's yours now");
            helper.assertFalse(pet.isInvulnerable(), "an ordinary pet");
            helper.assertTrue(kid.getPlayerReputation(player) < before, "the kid takes it as theft");
            helper.assertTrue(player.totalExperience == 0, "no reward");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("removal")
    public static void securityRequest(GameTestHelper helper) {
        Zombie guard = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        guard.setNoAi(true);
        ServerPlayer player = survivalPlayer(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        List<String> base = List.of("smalltalk", "goodbye");

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("guard/security"));
        helper.assertTrue(def != null && Security.isPlacement(def), "guard/security is a placement request");
        GuardQuests.tryOffer(guard, def);
        int count = guard.getData(QuestRegistries.GIVER).count();
        helper.assertTrue(count >= 10 && count <= 30, "10-30 blocks, got " + count);

        helper.assertFalse(GuardQuests.open(player, guard).options().contains("quest:guard_ask"), "strangers aren't asked (tier 1 only)");
        player.getData(QuestRegistries.GOODWILL).points.put(guard.getUUID(), 10.0);
        QuestDialogue.Opening open = GuardQuests.open(player, guard);
        helper.assertTrue(open.options().contains("quest:guard_ask"), "known to the guard: asked");
        GuardQuests.handle(player, guard, "quest:guard_ask", base);
        GuardQuests.handle(player, guard, "quest:accept", base);
        ActiveQuest quest = player.getData(QuestRegistries.PLAYER_QUESTS).quests.get(0);
        helper.assertTrue(quest.isActive() && quest.count == count, "accepted");

        // Measured from where the quest says the village is: a bell left by a neighbouring test can move it.
        BlockPos at = quest.giverPos.offset(2, 0, 2);
        Security.count(player, at, Blocks.TORCH.defaultBlockState(), +1);
        helper.assertTrue(quest.progress == 0, "a torch isn't a defense");
        Security.count(player, at.offset(500, 0, 0), Blocks.OAK_FENCE.defaultBlockState(), +1);
        helper.assertTrue(quest.progress == 0, "outside the village doesn't count");
        Security.count(player, at, Blocks.OAK_FENCE.defaultBlockState(), +1);
        Security.count(player, at, Blocks.OAK_FENCE.defaultBlockState(), -1);
        helper.assertTrue(quest.progress == 0, "place and break cancel out");
        for (int i = 0; i < count; i++) Security.count(player, at, Blocks.COBBLESTONE_WALL.defaultBlockState(), +1);
        helper.assertTrue(quest.progress == count, "walls count");

        double before = player.getData(QuestRegistries.GOODWILL).get(guard.getUUID());
        QuestDialogue.Opening done = GuardQuests.open(player, guard);
        helper.assertTrue(done.greeting().isPresent() && player.getData(QuestRegistries.PLAYER_QUESTS).quests.isEmpty(), "guard thanks you");
        int emeralds = player.getInventory().countItem(Items.EMERALD);
        helper.assertTrue(emeralds >= 1 && emeralds <= 5, "1-5 emeralds, got " + emeralds);
        helper.assertTrue(player.getData(QuestRegistries.GOODWILL).get(guard.getUUID()) > before, "guard trust goes up");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("removal")
    public static void securityAbandonPaysForProgress(GameTestHelper helper) {
        Zombie guard = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        guard.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);
        player.getData(QuestRegistries.GOODWILL).points.put(guard.getUUID(), 10.0);
        List<String> base = List.of("smalltalk", "goodbye");
        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("guard/security"));

        // Abandoned with nothing built: nothing paid.
        GuardQuests.tryOffer(guard, def);
        GuardQuests.handle(player, guard, "quest:accept", base);
        GuardQuests.handle(player, guard, "quest:quit", base);
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0, "no progress, no pay");

        // Abandoned when finished all but one block: most of the pay.
        guard.setData(QuestRegistries.GIVER, guard.getData(QuestRegistries.GIVER).cleared(0, ""));
        player.getData(QuestRegistries.PLAYER_QUESTS).quests.clear();
        GuardQuests.tryOffer(guard, def);
        int count = guard.getData(QuestRegistries.GIVER).count();
        GuardQuests.handle(player, guard, "quest:accept", base);
        ActiveQuest quest = player.getData(QuestRegistries.PLAYER_QUESTS).quests.get(0);
        // Next to wherever the quest anchored (the nearest meeting point may be another test's bell).
        BlockPos at = quest.giverPos.offset(2, 0, 2);
        for (int i = 0; i < count - 1; i++) Security.count(player, at, Blocks.OAK_FENCE_GATE.defaultBlockState(), +1);
        int xp = player.totalExperience;
        GuardQuests.handle(player, guard, "quest:quit", base);
        helper.assertTrue(quest.state == ActiveQuest.State.FAILED, "quit");
        helper.assertTrue(player.totalExperience > xp, "partial pay for partial work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void golemLostVillager(GameTestHelper helper) {
        net.minecraft.world.entity.animal.IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(2, 1, 2));
        golem.setNoAi(true);
        ServerPlayer player = survivalPlayer(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(4.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        helper.assertTrue(GuardQuests.tryOffer(golem, null, player) == null, "golems don't ask strangers");
        player.getData(QuestRegistries.GOODWILL).points.put(golem.getUUID(), 20.0);
        QuestDefinition def = GuardQuests.tryOffer(golem, null, player);
        helper.assertTrue(def != null && def.id().equals(QuestsModule.id("golem/lost_villager")) && def.isRescue(), "a trusted player: a lost villager");
        helper.assertTrue(GuardQuests.open(player, golem).options().contains("quest:guard_ask"), "the golem asks");

        // The quest as accepted (the search for a real forest is skipped here): the lost villager is right there.
        long now = helper.getLevel().getGameTime();
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), golem.getUUID(), golem.getName().copy(), helper.getLevel().dimension(),
                helper.absolutePos(new BlockPos(2, 1, 2)), helper.absolutePos(new BlockPos(6, 1, 6)), "forest", now, now + def.deadlineTicks(),
                ActiveQuest.State.ACTIVE, false, 0, 0);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        quests.quests.add(quest);
        // it only appears once its spot is ticking, which can take a few ticks in a fresh test area
        helper.startSequence().thenWaitUntil(() -> {
            GuardianManager.tickPlayer(player, quests, helper.getLevel().getGameTime());
            helper.assertTrue(quest.buddy != null && helper.getLevel().getEntity(quest.buddy) instanceof Villager, "the lost villager appears");
        }).thenExecute(() -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.Pillager.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(32)).isEmpty(), "lost, not kidnapped: no captors");
            quest.itemDropped = true; // found and following
            double before = player.getData(QuestRegistries.GOODWILL).get(golem.getUUID());
            GuardianManager.tickPlayer(player, quests, helper.getLevel().getGameTime());
            helper.assertTrue(quests.quests.isEmpty(), "home: done");
            helper.assertTrue(player.getData(QuestRegistries.GOODWILL).get(golem.getUUID()) > before, "the golem's trust goes up");
        }).thenSucceed();
    }

    /** A real survival player (the mock one is in creative, which every guard trusts). */
    private static net.minecraft.server.level.ServerPlayer survivalPlayer(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "test-survivor"), false);
        var player = new net.minecraft.server.level.ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}

package dev.villagechatter.life.law;

import dev.villagechatter.life.SecurityBlocks;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Law and order: who gets blamed, and what counts as village security. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class LawGameTests {
    private LawGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void securityBlocks(GameTestHelper helper) {
        helper.assertTrue(SecurityBlocks.isDefensive(Blocks.COBBLESTONE_WALL.defaultBlockState()), "walls count");
        helper.assertTrue(SecurityBlocks.isDefensive(Blocks.OAK_FENCE_GATE.defaultBlockState()), "gates count");
        helper.assertTrue(SecurityBlocks.isDefensive(Blocks.IRON_TRAPDOOR.defaultBlockState()), "trapdoors count");
        helper.assertFalse(SecurityBlocks.isDefensive(Blocks.WALL_TORCH.defaultBlockState()), "wall torches don't");
        helper.assertFalse(SecurityBlocks.isDefensive(Blocks.TRAPPED_CHEST.defaultBlockState()), "trapped chests don't");
        helper.assertFalse(SecurityBlocks.isDefensive(Blocks.STONE.defaultBlockState()), "stone doesn't");
        helper.succeed();
    }

    /** GoodDeeds finds who cured a zombie villager through this field. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void cureCredit(GameTestHelper helper) throws ReflectiveOperationException {
        helper.assertTrue(net.minecraft.world.entity.monster.ZombieVillager.class.getDeclaredField("conversionStarter").getType() == java.util.UUID.class,
                "the curer is still a UUID field");
        helper.succeed();
    }

    /** Only a player who was the direct cause is blamed: never a zombie, a fall or an explosion. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void crimeBlame(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        DamageSources sources = helper.getLevel().damageSources();
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        Wolf wolf = helper.spawn(EntityType.WOLF, new BlockPos(2, 2, 2));
        wolf.tame(player);
        helper.assertTrue(Blame.culprit(sources.playerAttack(player)) == player, "a player's hit blames them");
        helper.assertTrue(Blame.culprit(sources.mobAttack(wolf)) == player, "your wolf's bite blames you");
        helper.assertTrue(Blame.culprit(sources.mobAttack(zombie)) == null, "a zombie's hit blames nobody");
        helper.assertTrue(Blame.culprit(sources.fall()) == null, "a fall blames nobody");
        helper.assertTrue(Blame.culprit(sources.explosion(null, player)) == null, "explosions blame nobody");
        helper.succeed();
    }

    /** Hitting a villager in front of them is a crime they remember; stealing behind a villager's back isn't seen. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void witnessedCrime(GameTestHelper helper) {
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.BELL);
        var player = survivalPlayer(helper);
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        var bystander = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 12));
        bystander.setNoAi(true);
        bystander.setYRot(0); // facing +z, away from the player
        bystander.setYHeadRot(0);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(Law.commit(player, CrimeKind.THEFT, null, java.util.List.of(), null).isEmpty(),
                    "nobody saw the theft (the bystander looks away, and is too far to hear)");
            var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 2));
            var crime = Law.commit(player, CrimeKind.ASSAULT_VILLAGER, villager, null, null);
            helper.assertTrue(crime.isPresent(), "the villager you hit saw it");
            helper.assertTrue(villager.getPlayerReputation(player) < 0, "and thinks less of you");
            helper.assertTrue(crime.get().villageHit > 0 && !crime.get().reported, "with no guard or golem to tell, the rest of the village never hears of it");
            var again = Law.commit(player, CrimeKind.ASSAULT_VILLAGER, villager, null, null);
            helper.assertTrue(again.isPresent() && again.get() == crime.get() && crime.get().hits == 2, "a second hit adds to the first");
            CrimeLedger.get(helper.getLevel().getServer()).all(player.getUUID()).clear();
            helper.succeed();
        });
    }

    /** GameTestHelper's mock player counts as creative, and creative players commit no crimes. */
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

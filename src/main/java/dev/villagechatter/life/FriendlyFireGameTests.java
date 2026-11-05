package dev.villagechatter.life;

import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Pillager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** An allied pillager takes the first two hits as accidents; the third breaks the truce. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class FriendlyFireGameTests {
    private FriendlyFireGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void allyForgivesTwoHits(GameTestHelper helper) {
        ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "clumsy"));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Factions.adjust(player, Faction.PILLAGER, 200);
        Pillager ally = helper.spawn(EntityType.PILLAGER, new BlockPos(2, 1, 1));
        double standing = FactionBook.pillager(player);
        Runnable hit = () -> ally.hurt(player.damageSources().playerAttack(player), 1);
        helper.startSequence()
                .thenExecute(hit).thenIdle(15)
                .thenExecute(hit).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(FactionBook.truce(player, ally), "two hits are accidents: the truce holds");
                    helper.assertTrue(FactionBook.pillager(player) == standing, "and nobody holds them against you");
                    helper.assertTrue(ally.getTarget() != player, "the ally doesn't fight back");
                }).thenIdle(15)
                .thenExecute(hit).thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(!FactionBook.truce(player, ally), "the third hit breaks the truce");
                    helper.assertTrue(ally.getTarget() == player, "and the ally turns on you");
                })
                .thenSucceed();
    }
}

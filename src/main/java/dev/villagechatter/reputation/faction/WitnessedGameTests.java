package dev.villagechatter.reputation.faction;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.villagechatter.api.Factions;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.server.PiglinGifts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Word among piglins (and pillagers): it only counts once a witness tells someone, and dies with them for a day. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WitnessedGameTests {
    private WitnessedGameTests() {}

    private static Piglin piglin(GameTestHelper helper, int x) {
        Piglin p = helper.spawn(EntityType.PIGLIN, new BlockPos(x, 1, 2));
        p.setImmuneToZombification(true);
        p.setNoAi(true);
        return p;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "culprit"));
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 4));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        return player;
    }

    private static Vec3 spot(GameTestHelper helper) {
        return Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 3)));
    }

    /** One witness and nobody to tell: no loss. Killed before telling: never happened. */
    @GameTest(template = "empty", timeoutTicks = 60, batch = "solo_loneWitnessCantTell")
    public static void loneWitnessCantTell(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Piglin witness = piglin(helper, 2);
        double before = FactionBook.piglin(player);
        Witnessed.offense(player, Factions.Faction.PIGLIN, 5, spot(helper), null);
        helper.assertTrue(Witnessed.pending(player).size() == 1, "seen");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(FactionBook.piglin(player) == before, "nobody to tell: no loss");
            witness.hurt(helper.getLevel().damageSources().generic(), 1000F);
            helper.assertTrue(Witnessed.pending(player).isEmpty(), "the secret died with them");
            helper.assertTrue(FactionBook.piglin(player) == before, "never happened");
            helper.succeed();
        });
    }

    /** A witness tells another piglin: the loss lands. Kill both within the day: it's undone. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void toldThenSilenced(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Piglin witness = piglin(helper, 2);
        double before = FactionBook.piglin(player);
        Witnessed.offense(player, Factions.Faction.PIGLIN, 5, spot(helper), null);
        // A second piglin out of sight of the deed, but within earshot of the witness.
        Piglin confidant = helper.spawn(EntityType.PIGLIN, new BlockPos(2, 1, 0));
        confidant.setImmuneToZombification(true);
        confidant.setNoAi(true);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(FactionBook.piglin(player) <= before - 5 + 1e-6, "told: the loss lands");
            DamageSource quiet = helper.getLevel().damageSources().generic();
            witness.hurt(quiet, 1000F);
            helper.assertTrue(FactionBook.piglin(player) <= before - 5 + 1e-6, "one still knows");
            confidant.hurt(quiet, 1000F);
            helper.assertTrue(Math.abs(FactionBook.piglin(player) - before) < 1e-6, "everyone who knew is dead: undone");
            helper.succeed();
        });
    }

    /** Gifts by gold: a block beats an ingot beats a nugget; enchanted beats plain; a golden apple is eight ingots. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void piglinGiftValues(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        double nugget = PiglinGifts.value(server, new ItemStack(Items.GOLD_NUGGET));
        double ingot = PiglinGifts.value(server, new ItemStack(Items.GOLD_INGOT));
        double block = PiglinGifts.value(server, new ItemStack(Items.GOLD_BLOCK));
        double apple = PiglinGifts.value(server, new ItemStack(Items.GOLDEN_APPLE));
        ItemStack sword = new ItemStack(Items.GOLDEN_SWORD);
        double plain = PiglinGifts.value(server, sword);
        Holder<net.minecraft.world.item.enchantment.Enchantment> sharp = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemStack enchanted = sword.copy();
        enchanted.enchant(sharp, 3);
        helper.assertTrue(nugget < ingot && ingot < block, "nugget < ingot < block");
        helper.assertTrue(Math.abs(apple - 8) < 1e-6, "a golden apple is eight ingots, got " + apple);
        helper.assertTrue(plain >= 2 - 1e-6 && PiglinGifts.value(server, enchanted) > plain, "enchanted beats plain");
        helper.assertTrue(PiglinGifts.value(server, new ItemStack(Items.DIRT)) == 0, "dirt isn't gold");
        helper.assertTrue(PiglinGifts.gain(9) > PiglinGifts.gain(1) && PiglinGifts.gain(9) < 9 * PiglinGifts.gain(1), "value helps, slowly");
        helper.succeed();
    }
}

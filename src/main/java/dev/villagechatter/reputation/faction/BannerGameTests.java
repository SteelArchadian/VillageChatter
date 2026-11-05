package dev.villagechatter.reputation.faction;

import java.util.List;

import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Wearing the pillagers' colours (Factions v2, item 5 revised): each way to wear them, and what it buys you. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BannerGameTests {
    private BannerGameTests() {}

    private static ServerPlayer hated(GameTestHelper helper) {
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "turncoat"));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Factions.adjust(p, Faction.PILLAGER, -200);
        return p;
    }

    private static ItemStack banner(GameTestHelper helper) {
        return Raid.getLeaderBannerInstance(helper.getLevel().registryAccess().lookupOrThrow(Registries.BANNER_PATTERN));
    }

    /** The ominous pattern on a shield of any colour, in either hand. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerShieldEitherHand(GameTestHelper helper) {
        ServerPlayer player = hated(helper);
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponents.BASE_COLOR, DyeColor.RED);
        shield.set(DataComponents.BANNER_PATTERNS, banner(helper).get(DataComponents.BANNER_PATTERNS));
        helper.assertFalse(BannerLoyalty.wearing(player), "bare: not wearing them");
        player.setItemInHand(InteractionHand.MAIN_HAND, shield.copy());
        helper.assertTrue(BannerLoyalty.wearing(player), "main hand counts, whatever the base colour");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, shield.copy());
        helper.assertTrue(BannerLoyalty.wearing(player), "off hand counts");
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        helper.assertFalse(BannerLoyalty.wearing(player), "a plain shield doesn't");
        helper.succeed();
    }

    /** Right-click the banner in the air with a bare head: it goes on, and counts. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerOnHead(GameTestHelper helper) {
        ServerPlayer player = hated(helper);
        ItemStack held = banner(helper).copyWithCount(2);
        helper.assertTrue(BannerLoyalty.wear(player, held), "it goes on");
        helper.assertTrue(held.getCount() == 1 && player.getItemBySlot(EquipmentSlot.HEAD).getItem() == Items.WHITE_BANNER, "one taken");
        helper.assertTrue(BannerLoyalty.wearing(player), "and counts");
        helper.assertFalse(BannerLoyalty.wear(player, held), "not over something already on your head");
        helper.assertFalse(BannerLoyalty.wear(player, new ItemStack(Items.WHITE_BANNER)), "a plain banner isn't worn");
        helper.succeed();
    }

    /** Helmet + banner in a crafting grid: a crested helmet, which counts. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerCrestHelmet(GameTestHelper helper) {
        ServerPlayer player = hated(helper);
        var level = helper.getLevel();
        CraftingInput input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.IRON_HELMET), banner(helper)));
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent(), "helmet + ominous banner is a recipe");
        ItemStack crested = recipe.get().value().assemble(input, level.registryAccess());
        helper.assertTrue(crested.is(Items.IRON_HELMET) && BannerLoyalty.hasCrest(crested), "a crested helmet");
        CraftingInput plain = CraftingInput.of(2, 1, List.of(new ItemStack(Items.IRON_HELMET), new ItemStack(Items.WHITE_BANNER)));
        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, plain, level).isEmpty(), "a plain banner won't do");
        player.setItemSlot(EquipmentSlot.HEAD, crested);
        helper.assertTrue(BannerLoyalty.wearing(player), "wearing it counts");
        helper.succeed();
    }

    /** Hated, outpost pillagers won't talk; in the colours they read you as Tolerated and do. Patrols don't care. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerTruceAndTalk(GameTestHelper helper) {
        ServerPlayer player = hated(helper);
        var pillager = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        pillager.setNoAi(true);
        helper.assertFalse(FactionBook.truce(player, pillager) || PillagerTalk.canTalk(player, pillager), "hated: no truce, no talk");
        player.setItemSlot(EquipmentSlot.HEAD, banner(helper));
        helper.assertTrue(Factions.tier(player) == Factions.Tier.TOLERATED, "in the colours they read Tolerated");
        helper.assertTrue(FactionBook.truce(player, pillager) && PillagerTalk.canTalk(player, pillager), "truce, and they talk");
        pillager.setPatrolTarget(pillager.blockPosition().offset(200, 0, 0));
        helper.assertFalse(FactionBook.truce(player, pillager), "a patrol goes by your true standing");
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        Factions.adjust(player, Faction.PILLAGER, 400);
        player.setItemSlot(EquipmentSlot.HEAD, banner(helper));
        helper.assertTrue(Factions.tier(player) == Factions.Tier.ALLY, "the colours never lower what they think of you");
        helper.succeed();
    }

    /** Caught without the colours by a pillager costs a little (once per place); seen in them by a villager costs more. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerSightings(GameTestHelper helper) {
        ServerPlayer player = hated(helper);
        Factions.adjust(player, Faction.PILLAGER, 40); // below Tolerated but off the floor, so the loss shows
        var pillager = helper.spawn(EntityType.PILLAGER, new BlockPos(1, 1, 4));
        double before = FactionBook.pillager(player);
        BannerLoyalty.forget(player);
        BannerLoyalty.sightings(player);
        double after = FactionBook.pillager(player);
        helper.assertTrue(Math.abs(before - after - dev.villagechatter.ChatterConfig.BANNER_UNSEEN_LOSS.get()) < 1e-6,
                "seen without the colours: " + before + " -> " + after);
        helper.assertTrue(pillager.getTarget() == player, "and attacked");
        BannerLoyalty.sightings(player);
        helper.assertTrue(FactionBook.pillager(player) == after, "once per place");
        pillager.discard();

        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(4, 1, 1));
        villager.setNoAi(true);
        player.setItemSlot(EquipmentSlot.HEAD, banner(helper));
        double village = FactionBook.village(player);
        BannerLoyalty.sightings(player);
        helper.assertTrue(FactionBook.village(player) < village, "a villager sees you in their colours");
        BannerLoyalty.forget(player);
        helper.succeed();
    }
}

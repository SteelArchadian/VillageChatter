package dev.villagechatter.reputation;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Fable-style gifts: tastes per profession, rejections, the daily cap, and the village's share. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class GiftGameTests {
    private GiftGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    @SuppressWarnings("removal")
    public static void giftTastesAndLimits(GameTestHelper helper) {
        Villager smith = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        smith.setVillagerData(smith.getVillagerData().setProfession(VillagerProfession.ARMORER));
        smith.setNoAi(true);
        Villager neighbor = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        neighbor.setNoAi(true);
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 3));
        kid.setAge(-24000);
        kid.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        helper.assertTrue(Gifts.react(smith, new ItemStack(Items.ANVIL)) == Gifts.Reaction.LOVE, "an armorer loves an anvil");
        helper.assertTrue(Gifts.react(smith, new ItemStack(Items.IRON_INGOT)) == Gifts.Reaction.LIKE, "and likes iron");
        helper.assertTrue(Gifts.react(smith, new ItemStack(Items.STONE)) == Gifts.Reaction.MEH, "stone: 'I'll add it to my storage'");
        helper.assertTrue(Gifts.react(smith, new ItemStack(Items.WOODEN_HOE)) == Gifts.Reaction.REJECT, "a hoe isn't their kind of thing");
        helper.assertTrue(Gifts.react(kid, new ItemStack(Items.COOKIE)) == Gifts.Reaction.LOVE, "kids love cookies");
        helper.assertTrue(Gifts.react(kid, new ItemStack(Items.DIAMOND_SWORD)) == Gifts.Reaction.REJECT, "and don't want swords");

        player.getInventory().setItem(0, new ItemStack(Items.WOODEN_HOE));
        player.getInventory().setItem(1, new ItemStack(Items.ANVIL, 3));
        Gifts.Result rejected = Gifts.give(player, smith, 0);
        helper.assertTrue(rejected.reaction() == Gifts.Reaction.REJECT && player.getInventory().getItem(0).is(Items.WOODEN_HOE), "rejected gifts are handed back");

        Gifts.give(player, smith, 1);
        double afterOne = player.getData(QuestRegistries.GOODWILL).get(smith.getUUID()) + smith.getPlayerReputation(player);
        helper.assertTrue(afterOne >= 4 - 1e-6, "a loved gift raises their opinion by 4, got " + afterOne);
        helper.assertTrue(player.getInventory().getItem(1).getCount() == 2, "one anvil taken");
        double neighborShare = player.getData(QuestRegistries.GOODWILL).get(neighbor.getUUID());
        helper.assertTrue(neighborShare > 0 && neighborShare < 1, "the village hears about it, a little: " + neighborShare);

        Gifts.give(player, smith, 1);
        Gifts.Result third = Gifts.give(player, smith, 1);
        helper.assertTrue(third.reaction() == Gifts.Reaction.TOO_MANY, "two gifts a day, then 'you're too kind'");
        helper.assertTrue(player.getInventory().getItem(1).getCount() == 1, "the third anvil stays with you");
        helper.succeed();
    }

    /** Gifts by what they're made of: classes from tags and recipes, and how each side takes them. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void giftsByIngredients(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(GiftClasses.is(server, new ItemStack(Items.CAKE), GiftClasses.Kind.SWEET), "cake is sweet (sugar)");
        var beef = GiftClasses.of(server, new ItemStack(Items.COOKED_BEEF));
        helper.assertTrue(beef.contains(GiftClasses.Kind.MEAT) && beef.contains(GiftClasses.Kind.COOKED), "steak is cooked meat");
        var carrot = GiftClasses.of(server, new ItemStack(Items.GOLDEN_CARROT));
        helper.assertTrue(carrot.contains(GiftClasses.Kind.VEGETABLE) && carrot.contains(GiftClasses.Kind.GOLD), "a golden carrot is both");
        helper.assertTrue(GiftClasses.is(server, new ItemStack(Items.GOLDEN_SWORD), GiftClasses.Kind.GOLD), "golden swords are gold gear");

        Villager adult = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        adult.setVillagerData(adult.getVillagerData().setProfession(VillagerProfession.MASON));
        adult.setNoAi(true);
        Villager kid = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 1));
        kid.setAge(-24000);
        kid.setNoAi(true);
        helper.assertTrue(Gifts.react(adult, new ItemStack(Items.ROTTEN_FLESH)) == Gifts.Reaction.DISLIKE, "rotten food offends");
        helper.assertTrue(Gifts.react(adult, new ItemStack(Items.COOKED_MUTTON)).goodwill > 0, "any food pleases a grown-up");
        helper.assertTrue(Gifts.react(adult, new ItemStack(Items.COOKIE)) != Gifts.Reaction.DISLIKE, "sweets never cost anything");
        helper.assertTrue(Gifts.react(kid, new ItemStack(Items.PUMPKIN_PIE)) == Gifts.Reaction.LOVE, "kids love sweets");

        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "giver"));
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(1, 1, 3));
        piglin.setNoAi(true);
        piglin.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        dev.villagechatter.server.PiglinGifts.give(player, piglin, 0);
        helper.assertTrue(player.getInventory().getItem(0).is(Items.IRON_SWORD), "piglins only take gold gear");
        player.getInventory().setItem(0, new ItemStack(Items.GOLDEN_SWORD));
        dev.villagechatter.server.PiglinGifts.give(player, piglin, 0);
        helper.assertTrue(piglin.getMainHandItem().is(Items.GOLDEN_SWORD), "and wear it");

        var pillager = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        pillager.setNoAi(true);
        double before = dev.villagechatter.api.Factions.pillager(player);
        player.getInventory().setItem(0, new ItemStack(Items.COOKIE));
        dev.villagechatter.reputation.faction.PillagerGifts.give(player, pillager, 0);
        helper.assertTrue(dev.villagechatter.api.Factions.pillager(player) < before, "sweets insult a pillager");
        player.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF));
        dev.villagechatter.reputation.faction.PillagerGifts.give(player, pillager, 0);
        helper.assertTrue(player.getInventory().getItem(0).isEmpty() && dev.villagechatter.api.Factions.pillager(player) > before - 1,
                "meat goes down well");
        helper.succeed();
    }
}

package dev.villagechatter.reputation;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * What a gift is, without per-mod lists (Factions v2, item 8): an item's classes come from its tags (vanilla and the
 * common c: tags) and, failing those, from what it's made of, following crafting recipes down (a Supplementaries
 * pancake is bread and sugar, so it's sweet) and cooking recipes back (anything smelted from raw food is cooked).
 * Worked out once per recipe set and cached.
 */
public final class GiftClasses {
    private GiftClasses() {}

    public enum Kind { FOOD, COOKED, SWEET, MEAT, VEGETABLE, ROTTEN, NETHER, ENDER, GOLD, GEAR }

    /** Classes that pass from ingredients to what's made of them. */
    private static final EnumSet<Kind> INHERITED = EnumSet.of(Kind.SWEET, Kind.MEAT, Kind.VEGETABLE, Kind.ROTTEN, Kind.NETHER, Kind.ENDER, Kind.GOLD);

    private static final Set<Item> SWEET = Set.of(Items.SUGAR, Items.HONEY_BOTTLE, Items.HONEYCOMB, Items.COCOA_BEANS, Items.COOKIE,
            Items.CAKE, Items.PUMPKIN_PIE, Items.SWEET_BERRIES, Items.GLOW_BERRIES, Items.MELON_SLICE);
    private static final Set<Item> VEGETABLE = Set.of(Items.CARROT, Items.POTATO, Items.BAKED_POTATO, Items.BEETROOT, Items.GOLDEN_CARROT,
            Items.PUMPKIN, Items.DRIED_KELP, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM);
    private static final Set<Item> ROTTEN = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.FERMENTED_SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH);
    private static final Set<Item> NETHER = Set.of(Items.NETHER_WART, Items.CRIMSON_FUNGUS, Items.WARPED_FUNGUS, Items.BLAZE_POWDER,
            Items.BLAZE_ROD, Items.MAGMA_CREAM, Items.GHAST_TEAR, Items.SHROOMLIGHT, Items.WEEPING_VINES, Items.TWISTING_VINES,
            Items.CRIMSON_ROOTS, Items.WARPED_ROOTS, Items.QUARTZ);
    private static final Set<Item> ENDER = Set.of(Items.CHORUS_FRUIT, Items.POPPED_CHORUS_FRUIT, Items.ENDER_PEARL, Items.ENDER_EYE,
            Items.CHORUS_FLOWER, Items.SHULKER_SHELL);
    private static final Set<Item> GOLD = Set.of(Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.RAW_GOLD, Items.RAW_GOLD_BLOCK);

    private static final TagKey<Item> C_CANDY = tag("c", "foods/candy"), C_COOKIE = tag("c", "foods/cookie"),
            C_RAW_MEAT = tag("c", "foods/raw_meat"), C_COOKED_MEAT = tag("c", "foods/cooked_meat"), C_RAW_FISH = tag("c", "foods/raw_fish"),
            C_COOKED_FISH = tag("c", "foods/cooked_fish"), C_VEGETABLE = tag("c", "foods/vegetable"),
            C_POISON = tag("c", "foods/food_poisoning"), C_GOLDEN = tag("c", "foods/golden"), C_COOKED = tag("c", "foods/cooked_meat"),
            C_PIE = tag("c", "foods/pie"), C_ENDER = tag("c", "ender_pearls"), C_TOOLS = tag("c", "tools"), C_ARMORS = tag("c", "armors");

    private static TagKey<Item> tag(String ns, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static RecipeManager memoFor;
    private static final Map<Item, EnumSet<Kind>> MEMO = new HashMap<>();
    private static Set<Item> cooked = Set.of();

    public static synchronized Set<Kind> of(MinecraftServer server, ItemStack stack) {
        if (stack.isEmpty()) return EnumSet.noneOf(Kind.class);
        RecipeManager recipes = server.getRecipeManager();
        if (recipes != memoFor) {
            MEMO.clear();
            memoFor = recipes;
            cooked = cookedItems(server, recipes);
        }
        EnumSet<Kind> kinds = EnumSet.copyOf(classify(server, recipes, stack.getItem(), new HashSet<>()));
        if (stack.has(DataComponents.FOOD)) kinds.add(Kind.FOOD);
        return kinds;
    }

    public static boolean is(MinecraftServer server, ItemStack stack, Kind kind) {
        return of(server, stack).contains(kind);
    }

    private static EnumSet<Kind> classify(MinecraftServer server, RecipeManager recipes, Item item, Set<Item> visiting) {
        EnumSet<Kind> memo = MEMO.get(item);
        if (memo != null) return memo;
        EnumSet<Kind> kinds = own(item);
        if (cooked.contains(item)) kinds.add(Kind.COOKED);
        // made of: follow crafting recipes down a few levels (only where the item didn't say for itself)
        if (visiting.add(item) && visiting.size() <= 6) {
            for (var holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
                ItemStack result = holder.value().getResultItem(server.registryAccess());
                if (!result.is(item)) continue;
                for (Ingredient ing : holder.value().getIngredients()) {
                    ItemStack[] options = ing.getItems();
                    if (options.length == 0) continue;
                    EnumSet<Kind> from = classify(server, recipes, options[0].getItem(), visiting);
                    for (Kind k : from) if (INHERITED.contains(k)) kinds.add(k);
                }
            }
            visiting.remove(item);
        }
        // gear made of gold is gold gear; food made of meat isn't "gear"
        MEMO.put(item, kinds);
        return kinds;
    }

    /** What the item says about itself: its tags and the built-in lists. */
    static EnumSet<Kind> own(Item item) {
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        ItemStack s = item.getDefaultInstance();
        if (SWEET.contains(item) || s.is(C_CANDY) || s.is(C_COOKIE) || s.is(C_PIE)) kinds.add(Kind.SWEET);
        if (s.is(ItemTags.MEAT) || s.is(C_RAW_MEAT) || s.is(C_COOKED_MEAT) || s.is(C_RAW_FISH) || s.is(C_COOKED_FISH) || s.is(ItemTags.FISHES)) {
            kinds.add(Kind.MEAT);
        }
        if (VEGETABLE.contains(item) || s.is(C_VEGETABLE)) kinds.add(Kind.VEGETABLE);
        if (ROTTEN.contains(item) || s.is(C_POISON)) kinds.add(Kind.ROTTEN);
        if (NETHER.contains(item)) kinds.add(Kind.NETHER);
        if (ENDER.contains(item) || s.is(C_ENDER)) kinds.add(Kind.ENDER);
        if (GOLD.contains(item) || s.is(C_GOLDEN)) kinds.add(Kind.GOLD);
        if (s.is(C_COOKED_MEAT) || s.is(C_COOKED_FISH) || s.is(C_COOKED)) kinds.add(Kind.COOKED);
        if (item instanceof ArmorItem || item instanceof TieredItem || item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof TridentItem || s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(C_TOOLS) || s.is(C_ARMORS)) {
            kinds.add(Kind.GEAR);
        }
        if (item instanceof ArmorItem armor && armor.getMaterial().is(net.minecraft.world.item.ArmorMaterials.GOLD)) kinds.add(Kind.GOLD);
        if (item instanceof TieredItem tiered && tiered.getTier() == net.minecraft.world.item.Tiers.GOLD) kinds.add(Kind.GOLD);
        return kinds;
    }

    /** Everything a furnace, smoker or campfire makes out of food. */
    private static Set<Item> cookedItems(MinecraftServer server, RecipeManager recipes) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : java.util.List.of(RecipeType.SMELTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING)) {
            @SuppressWarnings("unchecked")
            var all = recipes.getAllRecipesFor((RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe>) type);
            for (var holder : all) {
                ItemStack result = holder.value().getResultItem(server.registryAccess());
                if (!result.has(DataComponents.FOOD)) continue;
                for (Ingredient ing : holder.value().getIngredients()) {
                    ItemStack[] options = ing.getItems();
                    if (options.length > 0 && options[0].has(DataComponents.FOOD)) out.add(result.getItem());
                }
            }
        }
        return out;
    }
}

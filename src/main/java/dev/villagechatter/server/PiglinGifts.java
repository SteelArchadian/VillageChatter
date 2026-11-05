package dev.villagechatter.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Gifts for piglins: gold, and anything made with it. What it's worth to them is the gold that went into it (a
 * nugget a ninth of an ingot, a block nine; a golden apple eight ingots; a golden sword two), worked out from the
 * crafting recipes, so modded gold items count too. Enchantments make it shinier still. Things they love that aren't
 * crafted from gold (the vanilla piglin_loved tag) are worth an ingot. Anything else they hand back.
 * Two gifts per piglin per day, like villagers; the gain grows slowly with value (a block beats a nugget, but not
 * eighty times over).
 */
public final class PiglinGifts {
    private PiglinGifts() {}

    public static final int PER_DAY = 2;
    private static final Map<Item, Double> BASE = Map.of(Items.GOLD_NUGGET, 1.0 / 9, Items.GOLD_INGOT, 1.0, Items.GOLD_BLOCK, 9.0,
            Items.RAW_GOLD, 1.0, Items.RAW_GOLD_BLOCK, 9.0, Items.GOLD_ORE, 1.0, Items.DEEPSLATE_GOLD_ORE, 1.0,
            Items.NETHER_GOLD_ORE, 0.5);

    private static RecipeManager memoFor;
    private static final Map<Item, Double> MEMO = new HashMap<>();
    /** "player|piglin" -> {day, gifts that day}. */
    private static final Map<String, long[]> GIVEN = new HashMap<>();

    /** Gold in it, in ingots, with enchantments counting extra. 0 = not something a piglin wants. */
    public static double value(MinecraftServer server, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        double v = gold(server, stack.getItem());
        if (v <= 0 && stack.is(ItemTags.PIGLIN_LOVED)) v = 1;
        if (v <= 0) return 0;
        int levels = 0;
        for (var e : stack.getEnchantments().entrySet()) levels += e.getIntValue();
        return v * (1 + 0.25 * levels);
    }

    static synchronized double gold(MinecraftServer server, Item item) {
        RecipeManager recipes = server.getRecipeManager();
        if (recipes != memoFor) {
            MEMO.clear();
            memoFor = recipes;
        }
        return gold(server, recipes, item, new HashSet<>());
    }

    private static double gold(MinecraftServer server, RecipeManager recipes, Item item, Set<Item> visiting) {
        Double base = BASE.get(item);
        if (base != null) return base;
        Double memo = MEMO.get(item);
        if (memo != null) return memo;
        if (!visiting.add(item) || visiting.size() > 6) return 0;
        double best = 0;
        for (var holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            var recipe = holder.value();
            ItemStack result = recipe.getResultItem(server.registryAccess());
            if (!result.is(item) || result.getCount() <= 0) continue;
            double sum = 0;
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] options = ing.getItems();
                if (options.length > 0) sum += gold(server, recipes, options[0].getItem(), visiting);
            }
            best = Math.max(best, sum / result.getCount());
        }
        visiting.remove(item);
        MEMO.put(item, best);
        return best;
    }

    /** Gives one of the item in that slot. Things that aren't gold stay with the player. */
    public static Component give(ServerPlayer player, Piglin piglin, int slot) {
        ItemStack stack = slot >= 0 && slot < player.getInventory().getContainerSize() ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
        if (stack.isEmpty()) return Component.translatable("villagequests.gift.nothing");
        double value = value(player.server, stack);
        var rnd = piglin.getRandom();
        var kinds = dev.villagechatter.reputation.GiftClasses.of(player.server, stack);
        // gear only if it's gold (they wear it); then vegetables and Nether things, then meat and sweets
        boolean gear = kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.GEAR);
        if (gear && !kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.GOLD)) value = 0;
        double food = 0;
        if (value <= 0 && !gear) {
            if (kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.VEGETABLE) || kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.NETHER)) food = 1;
            else if (kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.FOOD) && (kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.MEAT)
                    || kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.SWEET))) food = 0.4;
            if (kinds.contains(dev.villagechatter.reputation.GiftClasses.Kind.ROTTEN)) food = 0;
        }
        if (value <= 0 && food <= 0) {
            piglin.playSound(SoundEvents.PIGLIN_JEALOUS, 1.0F, piglin.getVoicePitch());
            return Component.translatable("villagechatter.piglin.gift.reject." + rnd.nextInt(3));
        }
        long day = player.server.overworld().getGameTime() / 24000L;
        String key = player.getUUID() + "|" + piglin.getUUID();
        long[] given = GIVEN.computeIfAbsent(key, k -> new long[] {day, 0});
        if (given[0] != day) {
            given[0] = day;
            given[1] = 0;
        }
        if (given[1] >= PER_DAY) return Component.translatable("villagechatter.piglin.gift.too_many." + rnd.nextInt(2));
        given[1]++;
        if (GIVEN.size() > 512) GIVEN.values().removeIf(g -> g[0] < day);
        Component name = stack.getHoverName();
        ItemStack one = stack.split(1);
        player.getInventory().setChanged();
        if (gear) wear(piglin, one);
        if (value <= 0) {
            FactionBook.adjust(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_GIFT_GAIN.get() * food);
            piglin.playSound(SoundEvents.PIGLIN_CELEBRATE, 1.0F, piglin.getVoicePitch());
            return Component.translatable("villagechatter.piglin.gift." + (food >= 1 ? "food" : "meh") + "." + rnd.nextInt(3), name);
        }
        FactionBook.adjust(player, Factions.Faction.PIGLIN, gain(value));
        piglin.playSound(SoundEvents.PIGLIN_ADMIRING_ITEM, 1.0F, piglin.getVoicePitch());
        if (piglin.level() instanceof ServerLevel level && value >= 8) {
            level.sendParticles(ParticleTypes.HEART, piglin.getX(), piglin.getEyeY() + 0.5, piglin.getZ(), 5, 0.4, 0.3, 0.4, 0);
        }
        String kind = value >= 8 ? "love" : value >= 1 ? "like" : "meh";
        return Component.translatable("villagechatter.piglin.gift." + kind + "." + rnd.nextInt(3), name);
    }

    /** Gold armor goes on bare skin; a golden sword or axe in an empty hand (a crossbow piglin keeps its crossbow). */
    static void wear(Piglin piglin, ItemStack gear) {
        net.minecraft.world.entity.EquipmentSlot slot = gear.getItem() instanceof net.minecraft.world.item.ArmorItem armor
                ? armor.getEquipmentSlot() : net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        if (!piglin.getItemBySlot(slot).isEmpty()) return;
        piglin.setItemSlot(slot, gear);
        piglin.setGuaranteedDrop(slot);
        piglin.setPersistenceRequired();
    }

    /** Standing for a gift worth {@code value} ingots: a nugget ~0.15, an ingot 1, a block ~3.3, a golden apple ~3.2. */
    public static double gain(double value) {
        return ChatterConfig.PIGLIN_GIFT_GAIN.get() * Math.log(1 + value) / Math.log(2);
    }
}

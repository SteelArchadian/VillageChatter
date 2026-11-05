package dev.villagechatter.reputation.faction;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.reputation.GiftClasses;
import dev.villagechatter.reputation.GiftClasses.Kind;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Gifts for any pillager or vindicator (Factions v2, item 8): weapons and armor, which they put on if they've a free
 * hand or slot for it, and meat, which they eat. Sweets insult them (a small loss). Anything else they hand back. Two a
 * day per pillager.
 */
public final class PillagerGifts {
    private PillagerGifts() {}

    public static final int PER_DAY = 2;
    /** "player|pillager" -> {day, gifts that day}. */
    private static final Map<String, long[]> GIVEN = new HashMap<>();

    public static Component give(ServerPlayer player, AbstractIllager illager, int slot) {
        ItemStack stack = slot >= 0 && slot < player.getInventory().getContainerSize() ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
        var rnd = illager.getRandom();
        if (stack.isEmpty()) return Component.translatable("villagequests.gift.nothing");
        Set<Kind> kinds = GiftClasses.of(player.server, stack);
        if (kinds.contains(Kind.SWEET)) {
            FactionBook.adjust(player, Faction.PILLAGER, -ChatterConfig.WRONG_GIFT_LOSS.get());
            illager.playSound(SoundEvents.PILLAGER_AMBIENT, 1.0F, 0.8F);
            return Component.translatable("villagechatter.pillager.gift.sweet." + rnd.nextInt(3));
        }
        boolean gear = kinds.contains(Kind.GEAR), meat = kinds.contains(Kind.MEAT) && kinds.contains(Kind.FOOD);
        if (!gear && !meat) return Component.translatable("villagechatter.pillager.gift.reject." + rnd.nextInt(3));
        long day = player.server.overworld().getGameTime() / 24000L;
        long[] given = GIVEN.computeIfAbsent(player.getUUID() + "|" + illager.getUUID(), k -> new long[] {day, 0});
        if (given[0] != day) {
            given[0] = day;
            given[1] = 0;
        }
        if (given[1] >= PER_DAY) return Component.translatable("villagechatter.pillager.gift.too_many." + rnd.nextInt(2));
        given[1]++;
        if (GIVEN.size() > 512) GIVEN.values().removeIf(g -> g[0] < day);
        Component name = stack.getHoverName();
        ItemStack one = stack.split(1);
        player.getInventory().setChanged();
        if (gear) wear(illager, one);
        else eat(illager, one);
        FactionBook.adjust(player, Faction.PILLAGER, ChatterConfig.PILLAGER_GIFT_GAIN.get() * (gear ? 1.5 : 1));
        illager.playSound(SoundEvents.PILLAGER_CELEBRATE, 1.0F, 1.0F);
        return Component.translatable("villagechatter.pillager.gift." + (gear ? "gear" : "meat") + "." + rnd.nextInt(3), name);
    }

    /** Meat: eaten on the spot, healing them by what it's worth. */
    static void eat(AbstractIllager illager, ItemStack meat) {
        var food = meat.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food != null) illager.heal(food.nutrition());
        illager.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
    }

    /** Puts gear on where it fits: a crossbow in a pillager's hand, a blade in a vindicator's, armor on bare skin. */
    static void wear(AbstractIllager illager, ItemStack gear) {
        EquipmentSlot slot;
        if (gear.getItem() instanceof ArmorItem armor) {
            slot = armor.getEquipmentSlot();
            if (!illager.getItemBySlot(slot).isEmpty()) return;
        } else if (illager instanceof Pillager ? gear.getItem() instanceof CrossbowItem
                : gear.is(net.minecraft.tags.ItemTags.AXES) || gear.is(net.minecraft.tags.ItemTags.SWORDS)) {
            slot = EquipmentSlot.MAINHAND;
        } else {
            return;
        }
        illager.setItemSlot(slot, gear);
        illager.setGuaranteedDrop(slot);
        illager.setPersistenceRequired();
    }
}

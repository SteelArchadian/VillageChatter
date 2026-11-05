package dev.villagechatter.life;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Guards notice what you do with their gear. When you close a guard's inventory, each equipment slot is compared
 * with what it held when you opened it:
 *  - taken and not replaced: a solid hit (bigger for better gear),
 *  - swapped for something worse: a mild to solid hit, by how much worse,
 *  - swapped for something better (or filling an empty slot): a mild to solid gain.
 * Equal swaps don't count. The change goes to your standing with that guard (see {@link GuardTrust}), and a quarter
 * of it to the village around them. Taking gear back always costs more than giving it earned, so it can't be farmed.
 */
public final class GuardGear {
    private GuardGear() {}

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private record Snapshot(LivingEntity guard, double[] values) {}

    private static final Map<UUID, Snapshot> OPEN = new HashMap<>();

    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LivingEntity guard = guardOf(event.getContainer());
        if (guard == null) return;
        if (ChatterConfig.LAW_AND_ORDER.get() && !player.isCreative() && !dev.villagechatter.life.law.Companion.isHiredBy(guard, player)) {
            // Nobody rummages through a guard's kit: you hand them gear through the dialogue ("Gift") instead.
            player.closeContainer();
            LivingVillage.say(player, guard, Component.translatable("villagechatter.life.guard_gear_handoff." + player.getRandom().nextInt(3)));
            return;
        }
        OPEN.put(player.getUUID(), new Snapshot(guard, values(guard)));
    }

    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Snapshot before = OPEN.remove(player.getUUID());
        if (before == null || !before.guard.isAlive()) return;
        double[] after = values(before.guard);
        // Your own hired guard: swap their kit freely; only upgrades count (gratitude, never a grudge).
        boolean hired = dev.villagechatter.life.law.Companion.isHiredBy(before.guard, player);
        double total = 0;
        boolean stole = false;
        for (int i = 0; i < SLOTS.length; i++) {
            double was = before.values[i], now = after[i];
            if (Math.abs(now - was) < 0.25 || (hired && now < was)) continue;
            if (was > 0 && now == 0) {
                total -= 6 + 4 * Math.min(1, was / 10);             // taken, nothing in its place: -6 to -10
                stole = true;
            } else {
                double rel = Math.min(1, Math.abs(now - was) / Math.max(1, Math.max(was, now)));
                total += Math.signum(now - was) * (2 + 4 * rel);   // worse: -2 to -6, better: +2 to +6
            }
        }
        if (total == 0) return;
        player.getData(QuestRegistries.GOODWILL).points.merge(before.guard.getUUID(), total, Double::sum);
        Reputation.addVillageGoodwill(player, before.guard, total / 4);
        if (total > 0) dev.villagechatter.life.law.Law.goodDeed(player, before.guard, total / 8, total / 4); // the others notice

        String line = total > 0 ? "better" : stole ? "taken" : "worse";
        LivingVillage.say(player, before.guard,
                Component.translatable("villagechatter.life.guard_gear_" + line + "." + player.getRandom().nextInt(3)));
        if (before.guard.level() instanceof ServerLevel level) {
            level.sendParticles(total > 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
                    before.guard.getX(), before.guard.getEyeY() + 0.4, before.guard.getZ(), total > 0 ? 6 : 3, 0.3, 0.3, 0.3, 0);
        }
    }

    /**
     * Handing a guard a weapon, shield or armor piece (the dialogue's gift picker). Only an upgrade for that slot is
     * accepted, a shield only if they carry none, and whatever they wore before is thrown away, not given back.
     */
    public static Component give(ServerPlayer player, Mob guard, int invSlot) {
        var inv = player.getInventory();
        ItemStack stack = invSlot >= 0 && invSlot < inv.getContainerSize() ? inv.getItem(invSlot) : ItemStack.EMPTY;
        EquipmentSlot slot = stack.getItem() instanceof ShieldItem ? EquipmentSlot.OFFHAND : guard.getEquipmentSlotForItem(stack);
        if (slot == EquipmentSlot.BODY) slot = EquipmentSlot.MAINHAND;
        double now = value(stack, slot);
        String key;
        if (now <= 0) key = "guard_gear_notgear";
        else if (slot == EquipmentSlot.OFFHAND && guard.getItemBySlot(slot).getItem() instanceof ShieldItem) key = "guard_gear_keep";
        else {
            double was = value(guard.getItemBySlot(slot), slot);
            if (now - was < 0.25) key = "guard_gear_keep";
            else {
                guard.setItemSlot(slot, stack.split(1));
                guard.setDropChance(slot, 0); // a gift, not loot
                inv.setChanged();
                double rel = Math.min(1, (now - was) / Math.max(1, now));
                double gain = 2 + 4 * rel;
                player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), gain, Double::sum);
                Reputation.addVillageGoodwill(player, guard, gain / 4);
                dev.villagechatter.life.law.Law.goodDeed(player, guard, gain / 8, gain / 4);
                if (guard.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, guard.getX(), guard.getEyeY() + 0.4, guard.getZ(), 6, 0.3, 0.3, 0.3, 0);
                }
                key = "guard_gear_better";
            }
        }
        return Component.translatable("villagechatter.life." + key + "." + player.getRandom().nextInt(3));
    }

    /** The guard behind an inventory screen, if it is one (any menu holding a guard, found by reflection). */
    private static LivingEntity guardOf(AbstractContainerMenu menu) {
        for (Class<?> c = menu.getClass(); c != null && c != AbstractContainerMenu.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Entity.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(menu) instanceof LivingEntity e && GuardTrust.isGuard(e)) return e;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static double[] values(LivingEntity guard) {
        double[] out = new double[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++) out[i] = value(guard.getItemBySlot(SLOTS[i]), SLOTS[i]);
        return out;
    }

    /** How good a piece of gear is for its slot: damage, armor, shield or bow; enchantments and wear count. */
    static double value(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        double[] base = {0};
        if (stack.getItem() instanceof CrossbowItem) base[0] = 6;
        else if (stack.getItem() instanceof BowItem) base[0] = 5;
        else if (stack.getItem() instanceof ShieldItem) base[0] = 4;
        else {
            EquipmentSlot modifierSlot = slot == EquipmentSlot.OFFHAND ? EquipmentSlot.MAINHAND : slot;
            stack.forEachModifier(modifierSlot, (attribute, modifier) -> {
                if (attribute.is(Attributes.ATTACK_DAMAGE)) base[0] += modifier.amount();
                else if (attribute.is(Attributes.ARMOR)) base[0] += modifier.amount();
                else if (attribute.is(Attributes.ARMOR_TOUGHNESS)) base[0] += modifier.amount() * 0.5;
            });
        }
        if (base[0] <= 0) return 0; // not gear (food, a stick...): doesn't count
        double wear = stack.isDamageableItem() ? 1 - stack.getDamageValue() / (double) stack.getMaxDamage() : 1;
        // Each enchantment counts the same, whatever it is or its level (the user's rule).
        int enchantments = stack.getEnchantments().size();
        return base[0] * (0.4 + 0.6 * wear) + enchantments * 1.0;
    }
}

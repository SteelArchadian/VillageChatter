package dev.villagechatter.life;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Two small things villagers do on their own.
 * Kin: hit a villager, and later one of their neighbours finds you and wants an apology (refuse twice and they swing at you).
 * Hand-me-downs: drop armor or a weapon near a villager, and they ask whether they can have it.
 */
public final class WorldEvents {
    private WorldEvents() {}

    public static final String APOLOGISE = "kin_apologise", REFUSE = "kin_refuse", GIVE = "gear_give", KEEP = "gear_keep";
    static final int DEMAND = 5, SORRY = 5, INSIST = 5, SWING = 5, ASK = 5, THANKS = 5, SHRUG = 5;
    /** The neighbour comes looking after this long, and gives up after this long (ticks). */
    private static final long KIN_AFTER = 2400, KIN_WITHIN = 3 * 24000;
    private static final double KIN_REACH = 6, GEAR_REACH = 10, VILLAGE_REACH = 48;

    /** You hit this villager: where and when, for the neighbour who comes asking. */
    private record Grievance(UUID victim, net.minecraft.core.BlockPos where, long when) {}

    /** A confrontation or a request under way: who's asking, about what. */
    private static final class Ask {
        final UUID speaker;
        final ItemEntity item;
        int refusals;

        Ask(UUID speaker, ItemEntity item) {
            this.speaker = speaker;
            this.item = item;
        }
    }

    private static final Map<UUID, Grievance> GRIEVANCES = new HashMap<>();
    private static final Map<UUID, Ask> ASKING = new HashMap<>();
    /** Tossed gear waiting to be noticed: the item entity and when it was dropped. */
    private static final Map<UUID, ItemEntity> TOSSED = new HashMap<>();

    public static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof Villager victim && !victim.isBaby() && event.getSource().getEntity() instanceof ServerPlayer player
                && !player.isCreative() && ChatterConfig.KIN_CONFRONTS.get()) {
            GRIEVANCES.put(player.getUUID(), new Grievance(victim.getUUID(), victim.blockPosition(), player.serverLevel().getGameTime()));
        }
    }

    public static void onToss(ItemTossEvent event) {
        ItemStack stack = event.getEntity().getItem();
        if (event.getPlayer() instanceof ServerPlayer player && ChatterConfig.GEAR_REQUESTS.get()
                && (stack.getItem() instanceof ArmorItem || stack.getItem() instanceof TieredItem)) {
            TOSSED.put(player.getUUID(), event.getEntity());
        }
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0 || player.isSpectator()) return;
        // A scene left by walking away ends with the conversation.
        if (ASKING.containsKey(player.getUUID()) && !ConversationManager.inConversation(player)) ASKING.remove(player.getUUID());
        if (ASKING.containsKey(player.getUUID()) || ConversationManager.inConversation(player)) return;
        check(player);
    }

    static void check(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        kin(player, level);
        gear(player, level);
    }

    private static void kin(ServerPlayer player, ServerLevel level) {
        Grievance g = GRIEVANCES.get(player.getUUID());
        if (g == null) return;
        long since = level.getGameTime() - g.when;
        if (since > KIN_WITHIN) {
            GRIEVANCES.remove(player.getUUID());
            return;
        }
        if (since < KIN_AFTER || player.blockPosition().distSqr(g.where) > VILLAGE_REACH * VILLAGE_REACH) return;
        Villager kin = nearest(player, level, KIN_REACH, v -> !v.getUUID().equals(g.victim) && !v.isSleeping());
        if (kin == null) return;
        GRIEVANCES.remove(player.getUUID());
        confront(player, kin);
    }

    static void confront(ServerPlayer player, Villager kin) {
        ASKING.put(player.getUUID(), new Ask(kin.getUUID(), null));
        ConversationManager.openScripted(player, kin, List.of(APOLOGISE, REFUSE), labels(APOLOGISE, REFUSE),
                line("kin.demand", DEMAND, player.serverLevel()));
    }

    private static void gear(ServerPlayer player, ServerLevel level) {
        ItemEntity item = TOSSED.get(player.getUUID());
        if (item == null) return;
        if (!item.isAlive() || item.tickCount > 1200) {
            TOSSED.remove(player.getUUID());
            return;
        }
        if (item.tickCount < 40 || !item.onGround()) return;
        Villager taker = nearest(player, level, GEAR_REACH, v -> !v.isSleeping() && v.hasLineOfSight(item)
                && v.getItemBySlot(slotFor(item.getItem())).isEmpty());
        if (taker == null) return;
        TOSSED.remove(player.getUUID());
        ASKING.put(player.getUUID(), new Ask(taker.getUUID(), item));
        ConversationManager.openScripted(player, taker, List.of(GIVE, KEEP), labels(GIVE, KEEP),
                Component.translatable("villagechatter.gear.ask." + level.random.nextInt(ASK), item.getItem().getHoverName()));
    }

    /** @return true if this was one of these little scenes (handled here). */
    public static boolean handle(ServerPlayer player, LivingEntity speaker, String action) {
        Ask ask = ASKING.get(player.getUUID());
        if (ask == null || !ask.speaker.equals(speaker.getUUID())) return false;
        ServerLevel level = player.serverLevel();
        switch (action) {
            case APOLOGISE -> finish(player, speaker, line("kin.sorry", SORRY, level));
            case REFUSE -> {
                if (++ask.refusals < 2) {
                    ConversationManager.scriptedLine(player, speaker, List.of(APOLOGISE, REFUSE), labels(APOLOGISE, REFUSE),
                            line("kin.insist", INSIST, level));
                } else {
                    finish(player, speaker, line("kin.swing", SWING, level));
                    speaker.swing(InteractionHand.MAIN_HAND);
                    player.hurt(level.damageSources().mobAttack(speaker), 2F);
                }
            }
            case GIVE -> {
                if (ask.item != null && ask.item.isAlive() && speaker instanceof Villager villager) {
                    ItemStack stack = ask.item.getItem().copy();
                    EquipmentSlot slot = slotFor(stack);
                    ask.item.discard();
                    villager.setItemSlot(slot, stack);
                    villager.setDropChance(slot, 2F); // what you gave them comes back if they die
                    villager.setPersistenceRequired();
                    finish(player, speaker, line("gear.thanks", THANKS, level));
                } else {
                    finish(player, speaker, line("gear.shrug", SHRUG, level));
                }
            }
            case KEEP -> finish(player, speaker, line("gear.shrug", SHRUG, level));
            default -> {
                if (!action.equals(DialogueAction.GOODBYE)) return false;
                ASKING.remove(player.getUUID());
            }
        }
        return true;
    }

    private static void finish(ServerPlayer player, LivingEntity speaker, Component line) {
        ASKING.remove(player.getUUID());
        ConversationManager.scriptedLine(player, speaker, List.of(DialogueAction.GOODBYE), Map.of(), line);
    }

    /** Armor goes on its own slot; a weapon or tool in the hand. */
    static EquipmentSlot slotFor(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : EquipmentSlot.MAINHAND;
    }

    private static Villager nearest(ServerPlayer player, ServerLevel level, double reach, java.util.function.Predicate<Villager> ok) {
        return level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(reach),
                        v -> v.isAlive() && !v.isBaby() && v.getTarget() == null && ok.test(v)).stream()
                .min(Comparator.comparingDouble(v -> v.distanceToSqr(player))).orElse(null);
    }

    private static Map<String, Component> labels(String... options) {
        Map<String, Component> out = new HashMap<>();
        for (String o : options) out.put(o, Component.translatable("villagechatter.option." + o));
        return out;
    }

    private static Component line(String pool, int count, ServerLevel level) {
        return Component.translatable("villagechatter." + pool + "." + level.random.nextInt(count));
    }

    public static void forget(UUID player) {
        ASKING.remove(player);
        TOSSED.remove(player);
    }
}

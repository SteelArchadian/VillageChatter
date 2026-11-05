package dev.villagechatter.life;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A guard hands over their gear (Guard Villagers' inventory screen) only to someone they actually trust: the Hero
 * of the Village, or a player whose standing with that particular guard is high enough. Standing with a guard is
 * the guard's own reputation (gossip, like a villager's), the goodwill earned with them through quests, and what
 * the village's guards as a whole think of you (see law.VillageStanding).
 */
public final class GuardTrust {
    private GuardTrust() {}

    /** Any non-villager mob with "guard" in its id (Guard Villagers' guards and the like), but not ocean guardians. */
    public static boolean isGuard(Entity entity) {
        return entity instanceof Mob && !(entity instanceof AbstractVillager)
                && !(entity instanceof net.minecraft.world.entity.monster.Guardian)
                && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().contains("guard");
    }

    public static double score(ServerPlayer player, Entity guard) {
        double reputation = 0;
        try {
            Object rep = guard.getClass().getMethod("getPlayerReputation", Player.class).invoke(guard, player);
            if (rep instanceof Integer i) reputation = i;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        double shared = guard instanceof net.minecraft.world.entity.LivingEntity member
                ? dev.villagechatter.life.law.VillageStanding.shared(player.serverLevel(), player, member) : 0;
        return reputation + player.getData(QuestRegistries.GOODWILL).get(guard.getUUID()) + shared;
    }

    /** May this player open this guard's inventory? Always yes for anything that isn't a guard. */
    public static boolean trusts(ServerPlayer player, Entity guard) {
        if (!isGuard(guard) || player.isCreative()) return true;
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) return true;
        return score(player, guard) >= ChatterConfig.GUARD_GEAR_TRUST.get();
    }

    public static void refuse(ServerPlayer player, Entity guard) {
        LivingVillage.say(player, guard, Component.translatable("villagechatter.life.guard_gear." + player.getRandom().nextInt(3)));
    }

    /** A direct right-click that skipped the dialogue (crouching, dialogue off): same rule. */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (trusts(player, event.getTarget())) return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        refuse(player, event.getTarget());
    }
}

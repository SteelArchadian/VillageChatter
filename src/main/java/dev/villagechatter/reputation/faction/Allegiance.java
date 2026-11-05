package dev.villagechatter.reputation.faction;

import java.util.Optional;

import dev.villagechatter.api.Factions;
import dev.villagechatter.life.GuardTrust;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * The two meters are independent, so you can be in good with both sides. Villagers and guards talk differently to
 * a friend who's also been seen with the pillagers, and pillagers differently to a village hero.
 */
public final class Allegiance {
    private Allegiance() {}

    private static final double CHANCE = 0.5, FRIEND = 20, HERO = 60;
    private static final int VARIANTS = 5;

    /** Villagers' and guards' pool for this player, or null if they're nothing special to the pillagers. */
    public static String villagePool(double village, Factions.Tier pillager) {
        if (pillager.ordinal() < Factions.Tier.TOLERATED.ordinal()) return null;
        return village >= FRIEND ? "both" : "pillager_friend";
    }

    /** Pillagers' pool: hero or not (to the villages), friend or not (to them). */
    public static String pillagerPool(boolean hero, Factions.Tier pillager) {
        boolean friend = pillager.ordinal() >= Factions.Tier.TOLERATED.ordinal();
        return (hero ? "hero_" : "") + (friend ? "friend" : "enemy");
    }

    public static Optional<Component> greeting(ServerPlayer player, LivingEntity speaker) {
        if (!FactionBook.enabled()) return Optional.empty();
        String pool = villagePool(FactionBook.village(player), FactionBook.tier(FactionBook.pillager(player)));
        if (pool == null || speaker.getRandom().nextDouble() >= CHANCE) return Optional.empty();
        String who = GuardTrust.isGuard(speaker) ? "guard" : "villager";
        return Optional.of(Component.translatable("villagechatter.allegiance." + who + "." + pool + "."
                + speaker.getRandom().nextInt(VARIANTS), player.getDisplayName()));
    }

    public static Component pillagerGreeting(ServerPlayer player) {
        boolean hero = FactionBook.village(player) >= HERO || player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE);
        String pool = pillagerPool(hero, FactionBook.tier(FactionBook.pillagerSeen(player)));
        return Component.translatable("villagechatter.allegiance.pillager." + pool + "." + player.getRandom().nextInt(VARIANTS),
                player.getDisplayName());
    }
}

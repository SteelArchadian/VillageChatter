package dev.villagechatter.quests.quest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Short thank-you discounts at one villager's shop ("come see my shop, I'll give you a good price today").
 * Stored per player as villager -> {until game time, fraction}. Applied when the trade screen opens: vanilla resets
 * special prices on every trade start and again when trading stops, so this never sticks to the villager.
 */
public final class Discounts {
    public record Discount(long until, double fraction) {
        static final Codec<Discount> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("until").forGetter(Discount::until),
                Codec.DOUBLE.fieldOf("fraction").forGetter(Discount::fraction)
        ).apply(i, Discount::new));
    }

    public static final Codec<Discounts> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Discount.CODEC).xmap(Discounts::new, d -> d.byVillager);

    public final Map<UUID, Discount> byVillager;

    public Discounts() {
        this.byVillager = new HashMap<>();
    }

    private Discounts(Map<UUID, Discount> map) {
        this.byVillager = new HashMap<>(map);
    }

    public static void grant(ServerPlayer player, Villager villager, double days, double fraction) {
        if (days <= 0 || fraction <= 0) return;
        long until = player.serverLevel().getGameTime() + Math.round(days * 24000);
        player.getData(QuestRegistries.DISCOUNTS).byVillager.put(villager.getUUID(), new Discount(until, fraction));
    }

    /** Fires inside Villager#openTradingScreen, after vanilla set its prices and before the offers are sent. */
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getContainer() instanceof MerchantMenu) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.containerMenu instanceof MerchantMenu) || player.serverLevel() == null) return;
        Villager villager = tradingVillager(player);
        if (villager != null) apply(player, villager);
    }

    /** Lowers the villager's current prices for this player if they have a running discount. @return true if applied. */
    public static boolean apply(ServerPlayer player, Villager villager) {
        Discounts discounts = player.getData(QuestRegistries.DISCOUNTS);
        Discount d = discounts.byVillager.get(villager.getUUID());
        if (d == null) return false;
        if (player.serverLevel().getGameTime() >= d.until) {
            discounts.byVillager.remove(villager.getUUID());
            return false;
        }
        for (MerchantOffer offer : villager.getOffers()) {
            int off = Math.max(1, (int) Math.floor(offer.getBaseCostA().getCount() * d.fraction));
            offer.addToSpecialPriceDiff(-off);
        }
        return true;
    }

    private static Villager tradingVillager(ServerPlayer player) {
        for (Villager v : player.serverLevel().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(16.0))) {
            if (v.getTradingPlayer() == player) return v;
        }
        return null;
    }
}

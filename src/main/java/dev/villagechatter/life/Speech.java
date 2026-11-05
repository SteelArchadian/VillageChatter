package dev.villagechatter.life;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * The Speech skill, Skyrim style: 0 to 100, raised by talking your way through things rather than by points.
 * Passing a speech check teaches most, failing one a little, and every trade a little more. Gains shrink as the
 * skill grows, so the last levels take real practice. It makes every check but bribes likelier (up to
 * speechCheckBonus at 100) and, like Skyrim's Haggling, takes a little off villagers' prices.
 */
public final class Speech {
    private Speech() {}

    /** Practice from one passed check, failed check and trade, at skill 0 (shrinking toward 100). */
    static final double PASS = 2.0, FAIL = 0.5, TRADE = 0.3;

    public static double skill(ServerPlayer player) {
        return player.getData(ChatterAttachments.SPEECH);
    }

    public static void set(ServerPlayer player, double skill) {
        player.setData(ChatterAttachments.SPEECH, Math.max(0, Math.min(100, skill)));
    }

    /** Raises the skill by {@code amount}, less the higher it already is; says so when it reaches a new level. */
    public static void practice(ServerPlayer player, double amount) {
        double before = skill(player);
        if (before >= 100 || amount <= 0) return;
        double after = Math.min(100, before + amount * ChatterConfig.SPEECH_GAIN.get() * (110 - before) / 110);
        set(player, after);
        if ((int) after > (int) before) {
            player.displayClientMessage(Component.translatable("villagechatter.speech.up", (int) after), true);
        }
    }

    /** Rolls a speech check and practises the skill from the result. */
    public static boolean roll(ServerPlayer player, double chance) {
        boolean pass = player.getRandom().nextDouble() < chance;
        practice(player, pass ? PASS : FAIL);
        return pass;
    }

    /** The check multiplier from skill: x1 at 0 up to x(1 + speechCheckBonus) at 100. */
    public static double checkFactor(ServerPlayer player) {
        return 1 + skill(player) / 100 * ChatterConfig.SPEECH_CHECK_BONUS.get();
    }

    /** Takes the skill's share (up to speechDiscount at 100) off each price; called as the trade screen opens. */
    public static void discount(ServerPlayer player, Villager villager) {
        double fraction = skill(player) / 100 * ChatterConfig.SPEECH_DISCOUNT.get();
        if (fraction <= 0) return;
        for (MerchantOffer offer : villager.getOffers()) {
            int off = (int) Math.floor(offer.getBaseCostA().getCount() * fraction);
            if (off > 0) offer.addToSpecialPriceDiff(-off);
        }
    }

    public static void onTradeEvent(net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) practice(player, TRADE);
    }

    /** Fires inside Villager#openTradingScreen, after vanilla set its prices and before the offers are sent. */
    public static void onContainerOpen(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open event) {
        if (!(event.getContainer() instanceof net.minecraft.world.inventory.MerchantMenu) || !(event.getEntity() instanceof ServerPlayer player)) return;
        for (Villager v : player.serverLevel().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(16.0))) {
            if (v.getTradingPlayer() == player) {
                discount(player, v);
                return;
            }
        }
    }
}

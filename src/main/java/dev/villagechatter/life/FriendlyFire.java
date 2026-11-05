package dev.villagechatter.life;

import java.util.HashMap;
import java.util.Map;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.life.law.Blame;
import dev.villagechatter.life.law.Companion;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Hitting someone on your side: a guard, your hired guard, or a pillager you're allied with. The first hits are taken
 * as accidents ("I'm on your side!"), each with a warning, and the ally doesn't fight back. One more within a minute
 * and they treat it as the attack it is: the crime or the broken truce goes through as usual, and they turn on you.
 */
public final class FriendlyFire {
    private FriendlyFire() {}

    /** Hits only count while they keep coming: a minute of peace and the slate is clean. */
    private static final long WINDOW = 1200;
    /** A flurry of hits within half a second is one blow. */
    private static final long SAME_BLOW = 10;
    static final int FIRST = 10, SECOND = 5, TURN = 5;

    private static final class Strikes {
        int count;
        long last;
        boolean forgiven;
    }

    private static final Map<String, Strikes> STRIKES = new HashMap<>();

    private static boolean enabled() {
        return ChatterConfig.FRIENDLY_FIRE.get();
    }

    private static String key(ServerPlayer player, LivingEntity ally) {
        return player.getUUID() + "|" + ally.getUUID();
    }

    /** Guards (unless you're wanted by them already), your hired guard, and pillagers you have a truce with. */
    public static boolean ally(ServerPlayer player, LivingEntity victim) {
        if (victim instanceof Raider raider) return FactionBook.enabled() && FactionBook.truce(player, raider);
        return Companion.isHiredBy(victim, player) || GuardTrust.isGuard(victim) && GuardTrust.score(player, victim) >= 0;
    }

    /** Runs before the law and the factions look at the hit (registered at the highest priority). */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!enabled() || victim.level().isClientSide()) return;
        ServerPlayer player = Blame.culprit(event.getSource());
        if (player == null || !ally(player, victim)) return;
        long now = player.serverLevel().getGameTime();
        Strikes s = STRIKES.computeIfAbsent(key(player, victim), k -> new Strikes());
        if (now - s.last > WINDOW) s.count = 0;
        if (s.count == 0 || now - s.last > SAME_BLOW) {
            s.count++;
            int forgive = ChatterConfig.FRIENDLY_HITS.get();
            s.forgiven = s.count <= forgive;
            String pool = victim instanceof Raider ? "pillager" : "guard";
            String step = !s.forgiven ? "turn" : s.count == forgive ? "second" : "first";
            int n = step.equals("turn") ? TURN : step.equals("second") ? SECOND : FIRST;
            LivingVillage.say(player, victim, Component.translatable(
                    "villagechatter.friendly." + pool + "." + step + "." + player.getRandom().nextInt(n)));
            if (!s.forgiven && victim instanceof net.minecraft.world.entity.Mob mob) {
                if (victim instanceof Raider) FactionBook.grudge(player);
                if (victim instanceof Raider) mob.addTag(dev.villagechatter.reputation.faction.BannerLoyalty.ORDERED_ATTACK);
                mob.setTarget(player);
            }
        }
        s.last = now;
        if (STRIKES.size() > 512) STRIKES.clear();
    }

    /** This hit was taken as an accident: the law and the factions let it go. */
    public static boolean forgiven(ServerPlayer player, LivingEntity victim) {
        if (!enabled()) return false;
        Strikes s = STRIKES.get(key(player, victim));
        return s != null && s.forgiven && player.serverLevel().getGameTime() - s.last <= SAME_BLOW;
    }

    /** An ally doesn't fight back over a forgiven hit. */
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!enabled() || !(event.getNewAboutToBeSetTarget() instanceof ServerPlayer player)) return;
        Strikes s = STRIKES.get(key(player, event.getEntity()));
        if (s != null && s.forgiven && player.serverLevel().getGameTime() - s.last <= WINDOW) event.setCanceled(true);
    }
}

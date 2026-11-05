package dev.villagechatter.life;

import dev.villagechatter.ChatterAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * How grubby you are (0 clean to 100 filthy), and how much of it is blood. It builds up slowly on the road, faster
 * in a fight; water washes it off, rain a little. Feeds charisma (api.SpeechCheck): grime puts people off, though
 * blood makes a threat more convincing. Stored as {grime, blood} on the player.
 */
public final class Grime {
    private Grime() {}

    public static final double MAX = 100;
    private static final double ROAD = 0.01, HURT = 1, KILL = 6, SWIM = 4, RAIN = 0.5;

    public static double grime(ServerPlayer player) {
        return player.getData(ChatterAttachments.GRIME).get(0);
    }

    public static double blood(ServerPlayer player) {
        return player.getData(ChatterAttachments.GRIME).get(1);
    }

    public static void add(ServerPlayer player, double grime, double blood) {
        var v = player.getData(ChatterAttachments.GRIME);
        double b = clamp(v.get(1) + blood);
        double g = Math.max(b, clamp(v.get(0) + grime + Math.max(0, blood)));
        player.setData(ChatterAttachments.GRIME, java.util.List.of(g, b));
    }

    /** Water washes you; rain helps a little. Once a second. */
    static void washOrDirty(ServerPlayer player) {
        if (player.isInWater()) add(player, -SWIM, -SWIM);
        else if (player.level().isRainingAt(player.blockPosition().above())) add(player, -RAIN, -RAIN);
        else add(player, ROAD, 0);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 20 == 0 && !player.isSpectator()) washOrDirty(player);
    }

    public static void onHurt(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0) add(player, HURT, HURT / 2);
    }

    /** A kill up close leaves its mark. */
    public static void onKill(LivingDeathEvent event) {
        if (event.getSource().getDirectEntity() instanceof ServerPlayer player) add(player, 0, KILL);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(MAX, v));
    }
}

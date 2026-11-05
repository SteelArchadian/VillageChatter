package dev.villagechatter.client;

import dev.villagechatter.ChatterConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Conversation camera (Skyrim/Oblivion feel, AFK-cinema style framing):
 *  - the camera eases onto the speaker's face, smoothly, every rendered frame (not per game tick, so no stutter),
 *  - pushes in, then after a few seconds slowly alternates between a few gentle framings with a light sway,
 *  - eases back out when the conversation ends, leaving the player facing where the camera looked.
 * Pure FOV + camera-angle work; the player's own rotation is only touched once, at the end.
 */
public final class DialogueCamera {
    private DialogueCamera() {}

    private static final int FOCUS_MS = 900;
    private static final int PUSH_IN_MS = 750;
    private static final int EASE_OUT_MS = 600;
    private static final int SHOTS_START_MS = 4000;
    private static final int SHOT_PERIOD_MS = 6500;
    private static final int SHOT_BLEND_MS = 1500;

    /** Framings to alternate between once the conversation lingers: yaw offset, pitch offset (degrees), extra zoom. */
    private static final float[][] SHOTS = {
            {0F, 0F, 0F},
            {6.5F, -1.8F, 0.03F},
            {-5.5F, 2.2F, -0.02F},
    };

    private static Entity target;
    private static long startMs;
    private static long endMs = -1;
    private static float endBlend, endPush, endBlur;
    private static float lastYaw, lastPitch;
    private static boolean haveAim;

    static void start(Entity speaker) {
        target = speaker;
        startMs = Util.getMillis();
        endMs = -1;
        haveAim = false;
    }

    static void stop() {
        if (target == null || endMs >= 0) return;
        long now = Util.getMillis();
        endBlend = liveBlend(now);
        endPush = livePush(now);
        endBlur = liveBlend(now);
        endMs = now;
        // Leave the player facing where the camera ended up, so easing out doesn't swing the view back.
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && haveAim && ChatterConfig.CAMERA_ENABLED.get()) {
            player.setYRot(lastYaw);
            player.yRotO = lastYaw;
            player.setXRot(lastPitch);
            player.xRotO = lastPitch;
            player.setYHeadRot(lastYaw);
            endBlend = 0F; // already facing it: nothing left to ease
        }
    }

    /** True while talking (not while easing out). */
    static boolean inConversation() {
        return target != null && endMs < 0;
    }

    static Entity target() {
        return target;
    }

    // ------------------------------------------------------------------ curves

    private static float smooth(float x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static float liveBlend(long now) {
        return smooth((now - startMs) / (float) FOCUS_MS);
    }

    private static float livePush(long now) {
        return ChatterConfig.CAMERA_ZOOM.get().floatValue() * smooth((now - startMs) / (float) PUSH_IN_MS);
    }

    /** 1 -> 0 while easing out; clears the target once finished. */
    private static float outFactor(long now) {
        if (endMs < 0) return 1;
        float k = 1 - (now - endMs) / (float) EASE_OUT_MS;
        if (k <= 0) {
            target = null;
            return 0;
        }
        return smooth(k);
    }

    private static float blend(long now) {
        if (target == null) return 0;
        return endMs < 0 ? liveBlend(now) : endBlend * outFactor(now);
    }

    static float push(long now) {
        if (target == null) return 0;
        return endMs < 0 ? livePush(now) : endPush * outFactor(now);
    }

    /** 0..1 strength for the background blur. */
    static float blur(long now) {
        if (target == null || !ChatterConfig.CAMERA_ENABLED.get()) return 0;
        return endMs < 0 ? liveBlend(now) : endBlur * outFactor(now);
    }

    /** Current framing offset {yaw, pitch, zoom}: gliding from one shot to the next. */
    private static float[] shot(long now) {
        long t = now - startMs - SHOTS_START_MS;
        if (t < 0 || endMs >= 0) return SHOTS[0];
        int index = (int) (t / SHOT_PERIOD_MS);
        float k = smooth((t % SHOT_PERIOD_MS) / (float) SHOT_BLEND_MS);
        float[] from = SHOTS[index % SHOTS.length], to = SHOTS[(index + 1) % SHOTS.length];
        return new float[] {Mth.lerp(k, from[0], to[0]), Mth.lerp(k, from[1], to[1]), Mth.lerp(k, from[2], to[2])};
    }

    // ------------------------------------------------------------------ events

    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!ChatterConfig.CAMERA_ENABLED.get() || !event.usedConfiguredFov() || target == null) return;
        long now = Util.getMillis();
        float zoom = push(now) + shot(now)[2] * blend(now);
        if (zoom != 0) event.setFOV(event.getFOV() * (1 - zoom));
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!ChatterConfig.CAMERA_ENABLED.get() || target == null) return;
        long now = Util.getMillis();
        float b = blend(now);
        if (b <= 0) return;

        // Aim at the speaker's face, from where the camera actually is, this very frame.
        Vec3 eye = event.getCamera().getPosition();
        Vec3 face = target.getEyePosition((float) event.getPartialTick());
        Vec3 d = face.subtract(eye);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float aimYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F;
        float aimPitch = (float) -(Mth.atan2(d.y, flat) * Mth.RAD_TO_DEG);
        lastYaw = aimYaw;
        lastPitch = aimPitch;
        haveAim = true;

        float[] framing = shot(now);
        float sway = ChatterConfig.CAMERA_SWAY.get() ? smooth((now - startMs - 1500) / 2500F) : 0F;
        double s = (now - startMs) / 1000.0;
        float swayYaw = sway * 0.5F * (float) Math.sin(s * Math.PI * 2 / 9.0);
        float swayPitch = sway * 0.25F * (float) Math.sin(s * Math.PI * 2 / 13.0 + 1);
        float swayRoll = sway * 0.25F * (float) Math.sin(s * Math.PI * 2 / 11.0 + 2);

        float wantYaw = aimYaw + framing[0] + swayYaw;
        float wantPitch = Mth.clamp(aimPitch + framing[1] + swayPitch, -89F, 89F);
        event.setYaw(event.getYaw() + Mth.wrapDegrees(wantYaw - event.getYaw()) * b);
        event.setPitch(Mth.lerp(b, event.getPitch(), wantPitch));
        event.setRoll(event.getRoll() + swayRoll * b);
    }

    /** No longer turns the player per tick (that caused the stutter); kept so the listener registration stays valid. */
    public static void onClientTick(ClientTickEvent.Post event) {
    }

    /** Cinematic: hide the hotbar, hearts and crosshair while talking. */
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (inConversation() && ChatterConfig.HIDE_HUD.get()) event.setCanceled(true);
    }
}

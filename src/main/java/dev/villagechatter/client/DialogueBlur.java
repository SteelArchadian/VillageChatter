package dev.villagechatter.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.mojang.blaze3d.pipeline.RenderTarget;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.VillageChatter;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Depth-of-field background blur during conversations: the speaker stays sharp, the world behind softens.
 * One cheap post pass, only while a conversation is on screen.
 *
 * With an Iris shaderpack active (Bliss, Complementary...) vanilla post effects don't apply, so this steps
 * aside; turn on the pack's own Depth of Field instead. Since the dialogue camera centers the speaker,
 * the pack's auto-focus lands on them.
 */
public final class DialogueBlur {
    private DialogueBlur() {}

    private static PostChain chain;
    private static int chainWidth, chainHeight;
    private static boolean failed;

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!ChatterConfig.BACKGROUND_BLUR.get() || failed || IrisCompat.shaderPackInUse()) return;
        float strength = DialogueCamera.blur(Util.getMillis()) * ChatterConfig.BLUR_STRENGTH.get().floatValue();
        Entity target = DialogueCamera.target();
        if (strength < 0.01F || target == null) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main,
                        VillageChatter.id("shaders/post/dialogue_dof.json"));
                chainWidth = -1;
            }
            if (chainWidth != main.width || chainHeight != main.height) {
                chain.resize(main.width, main.height);
                chainWidth = main.width;
                chainHeight = main.height;
            }
            float focus = (float) event.getCamera().getPosition().distanceTo(target.getEyePosition());
            chain.setUniform("FocusDistance", Math.max(focus, 0.5F));
            chain.setUniform("Strength", strength);
            chain.setUniform("Near", 0.05F);
            chain.setUniform("Far", mc.gameRenderer.getDepthFar());
            chain.process(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            main.bindWrite(false);
        } catch (Exception e) {
            failed = true;
            VillageChatter.LOGGER.warn("Village Chatter: dialogue blur disabled, shader failed to load", e);
            close();
        }
    }

    /** Shaders get rebuilt on resource reload (F3+T); drop ours so it's recreated. */
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            close();
            failed = false;
        });
    }

    private static void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
    }

    /** Soft dependency on Iris (and forks) through its public API, without compiling against it. */
    static final class IrisCompat {
        private static final MethodHandle IN_USE = find();

        private static MethodHandle find() {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                return MethodHandles.publicLookup()
                        .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                        .bindTo(instance);
            } catch (Throwable t) {
                return null;
            }
        }

        static boolean shaderPackInUse() {
            if (IN_USE == null) return false;
            try {
                return (boolean) IN_USE.invoke();
            } catch (Throwable t) {
                return false;
            }
        }
    }
}

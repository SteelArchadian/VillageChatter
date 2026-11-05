package dev.villagechatter.client;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterConfig.DialogueTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Dev only (-Dvillagechatter.themePreview, gradle runClient -PvcThemePreview): draws a sample conversation in every
 * dialogue theme into an offscreen 1600x900 image at GUI scale 3 (so it works with the window hidden), saves each to
 * run/screenshots/theme-&lt;id&gt;.png, then quits.
 */
public final class ThemePreview extends Screen {
    private static final int W = 1600, H = 900, SCALE = 3;
    private static final List<Component> OPTIONS = List.of(Component.literal("What do you sell?"),
            Component.literal("Heard any news?"), Component.literal("Could you use a hand with anything around here?"),
            Component.literal("Goodbye"));
    private static final String TEXT = "Morning! The wheat's coming in thick this year, and the smith says the iron "
            + "golem has been pacing the wall again. Anything I can do for you?";

    private int ticks;

    private ThemePreview() {
        super(Component.literal("Theme preview"));
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (Boolean.getBoolean("villagechatter.themePreview") && event.getNewScreen() instanceof TitleScreen) {
            event.setNewScreen(new ThemePreview());
        }
    }

    @Override
    public void tick() {
        if (++ticks != 20) return;
        Minecraft mc = Minecraft.getInstance();
        DialogueTheme original = ChatterConfig.DIALOGUE_THEME.get();
        TextureTarget target = new TextureTarget(W, H, true, Minecraft.ON_OSX);
        int gw = W / SCALE, gh = H / SCALE;
        for (DialogueTheme theme : DialogueTheme.values()) {
            ChatterConfig.DIALOGUE_THEME.set(theme);
            target.setClearColor(0, 0, 0, 1);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, gw, gh, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            var view = RenderSystem.getModelViewStack();
            view.pushMatrix();
            view.translation(0, 0, -11000);
            RenderSystem.applyModelViewMatrix();
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            backdrop(g, gw, gh);
            DialogueFrame f = new DialogueFrame(font, gw, gh, ThemeStyle.current(), TEXT, OPTIONS);
            float opacity = ChatterConfig.BOX_OPACITY.get().floatValue();
            f.paintBox(g, font, Component.literal("Armorer Wren"), opacity);
            int y = f.textY();
            for (String line : f.lines) {
                Component c = Component.literal(line).withStyle(f.theme.textStyle());
                g.drawString(font, c, f.lineX(font, c), y, f.theme.text, f.theme.shadow);
                y += DialogueFrame.LINE_HEIGHT;
            }
            f.paintOptions(g, font, 0, -1, 0, 1F, 0, opacity, 0);
            g.drawString(font, theme.id(), 4, 4, 0xFFFFFFFF, true);
            g.flush();
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            Screenshot.grab(mc.gameDirectory, "theme-" + theme.id() + ".png", target, msg -> {});
        }
        target.destroyBuffers();
        mc.getMainRenderTarget().bindWrite(true);
        ChatterConfig.DIALOGUE_THEME.set(original);
        mc.stop();
    }

    /** Sky, a far hill and grass, with a few dark trunks: enough to judge how see-through each theme is. */
    private static void backdrop(GuiGraphics g, int w, int h) {
        g.fillGradient(0, 0, w, h / 2, 0xFF78A8E8, 0xFFB8D4F0);
        g.fill(0, h / 2, w, h, 0xFF5E9A3A);
        g.fillGradient(0, h / 2, w, h, 0xFF6EAE44, 0xFF3E7424);
        for (int x = 20; x < w; x += 70) g.fill(x, h / 3, x + 10, h, 0xFF4A3420);
    }
}

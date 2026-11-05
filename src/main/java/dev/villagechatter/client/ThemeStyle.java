package dev.villagechatter.client;

import java.io.Reader;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterConfig.DialogueFont;
import dev.villagechatter.ChatterConfig.DialogueTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * One dialogue theme (the dialogueTheme setting): nine-slice textures for the box, name tab, choices and cursor, plus
 * its font and colours, read from textures/gui/dialogue/&lt;theme&gt;/theme.json (made by tools/dialogue_themes.py, so a
 * resource pack can restyle a theme). Fills follow the boxOpacity setting, so every theme stays see-through.
 */
public final class ThemeStyle {
    private static final Map<DialogueTheme, ThemeStyle> CACHE = new EnumMap<>(DialogueTheme.class);
    private static final ResourceLocation SMOOTH = ResourceLocation.fromNamespaceAndPath("villagechatter", "dialogue");

    /** A nine-slice texture: its size and corner border in texture px, and whether the middle tiles or stretches. */
    private record Part(ResourceLocation tex, int w, int h, int bx, int by, boolean tile) {}

    private final Map<String, Part> parts = new HashMap<>();
    final int scale;
    final ResourceLocation font, nameFont;
    final int text, name, option, selected, narration;
    final boolean shadow, italic, tabBare;
    /** Layout: choices inside the box past a divider; text centred; name tab centred and in capitals; a divider line
     *  between the box and the choices outside it. */
    final boolean inline, center, tabCenter, nameUpper, columnDivider;
    /** The divider's colour (0 = none). */
    final int divider;
    final float boxAlpha, optAlpha, selAlpha, frameAlpha, minAlpha;
    final ItemStack tabIcon;

    private ThemeStyle(String theme, JsonObject json) {
        scale = json.get("scale").getAsInt();
        JsonObject ps = json.getAsJsonObject("parts");
        for (String key : ps.keySet()) {
            JsonObject p = ps.getAsJsonObject(key);
            parts.put(key, new Part(ResourceLocation.fromNamespaceAndPath("villagechatter", "textures/gui/dialogue/" + theme + "/" + key + ".png"),
                    p.get("w").getAsInt(), p.get("h").getAsInt(), p.get("bx").getAsInt(), p.get("by").getAsInt(),
                    p.has("tile") && p.get("tile").getAsBoolean()));
        }
        font = ResourceLocation.parse(json.get("font").getAsString());
        nameFont = json.has("nameFont") ? ResourceLocation.parse(json.get("nameFont").getAsString()) : font;
        text = color(json, "text");
        name = color(json, "name");
        option = color(json, "option");
        selected = color(json, "selected");
        narration = color(json, "narration");
        shadow = json.get("shadow").getAsBoolean();
        italic = flag(json, "italic");
        tabBare = flag(json, "tabBare");
        inline = flag(json, "inline");
        center = flag(json, "center");
        tabCenter = flag(json, "tabCenter");
        nameUpper = flag(json, "nameUpper");
        columnDivider = flag(json, "columnDivider");
        divider = json.has("divider") ? color(json, "divider") : 0;
        JsonObject alpha = json.getAsJsonObject("alpha");
        boxAlpha = alpha.get("box").getAsFloat();
        optAlpha = alpha.get("opt").getAsFloat();
        selAlpha = alpha.get("sel").getAsFloat();
        frameAlpha = alpha.get("frame").getAsFloat();
        minAlpha = json.has("minAlpha") ? json.get("minAlpha").getAsFloat() : 0F;
        tabIcon = json.has("tabIcon")
                ? new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(json.get("tabIcon").getAsString())))
                : ItemStack.EMPTY;
    }

    private static boolean flag(JsonObject json, String key) {
        return json.has(key) && json.get(key).getAsBoolean();
    }

    private static int color(JsonObject json, String key) {
        return 0xFF000000 | Integer.parseInt(json.get(key).getAsString().substring(1), 16);
    }

    /** The chosen theme; Classic if a theme's files can't be read. */
    static ThemeStyle current() {
        DialogueTheme theme = ChatterConfig.DIALOGUE_THEME.get();
        ThemeStyle style = CACHE.computeIfAbsent(theme, ThemeStyle::load);
        if (style == null) style = CACHE.computeIfAbsent(DialogueTheme.CLASSIC, ThemeStyle::load);
        return style;
    }

    private static ThemeStyle load(DialogueTheme theme) {
        String id = theme.id();
        var rl = ResourceLocation.fromNamespaceAndPath("villagechatter", "textures/gui/dialogue/" + id + "/theme.json");
        try (Reader reader = Minecraft.getInstance().getResourceManager().openAsReader(rl)) {
            return new ThemeStyle(id, JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception e) {
            LogUtils.getLogger().error("Village Chatter: can't read dialogue theme {}", rl, e);
            return null;
        }
    }

    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> CACHE.clear());
    }

    // ------------------------------------------------------------------ fonts

    /** The speech font: the theme's own, or the player's choice (dialogueFont). */
    Style textStyle() {
        return Style.EMPTY.withFont(pick(font)).withItalic(italic ? Boolean.TRUE : null);
    }

    Style nameStyle() {
        return Style.EMPTY.withFont(pick(nameFont));
    }

    Style optionStyle() {
        return Style.EMPTY.withFont(pick(font));
    }

    private static ResourceLocation pick(ResourceLocation themed) {
        DialogueFont choice = ChatterConfig.DIALOGUE_FONT.get();
        return switch (choice) {
            case THEME -> themed;
            case MINECRAFT -> Style.DEFAULT_FONT;
            case SMOOTH -> SMOOTH;
        };
    }

    // ------------------------------------------------------------------ drawing

    boolean has(String part) {
        return parts.containsKey(part);
    }

    /** Fill alpha for a part, from the boxOpacity setting and this theme's own mix. */
    float fill(float opacity, float mix) {
        return Math.max(minAlpha, Math.min(1F, opacity * mix));
    }

    /** Height of a part in GUI px (for strips and icons drawn at their own size). */
    int height(String part) {
        Part p = parts.get(part);
        return p == null ? 0 : p.h / scale;
    }

    int width(String part) {
        Part p = parts.get(part);
        return p == null ? 0 : p.w / scale;
    }

    /** A part at its own size. */
    void sprite(GuiGraphics g, String part, int x, int y, float alpha) {
        Part p = parts.get(part);
        if (p == null || alpha <= 0) return;
        begin(g, alpha);
        g.blit(p.tex, x, y, p.w / scale, p.h / scale, 0, 0, p.w, p.h, p.w, p.h);
        end(g);
    }

    /** A part repeated across a row, w GUI px long (Blocky's grass). */
    void row(GuiGraphics g, String part, int x, int y, int w, float alpha) {
        Part p = parts.get(part);
        if (p == null || alpha <= 0) return;
        begin(g, alpha);
        tiled(g, p, x, y, w, p.h / scale, 0, 0, p.w, p.h);
        end(g);
    }

    /** A part nine-sliced to w x h: corners kept, edges and middle stretched (or tiled). */
    void nine(GuiGraphics g, String part, int x, int y, int w, int h, float alpha) {
        Part p = parts.get(part);
        if (p == null || alpha <= 0 || w <= 0 || h <= 0) return;
        int gx = Math.min(p.bx / scale, w / 2), gy = Math.min(p.by / scale, h / 2);
        int[] dx = {0, gx, w - gx, w}, dy = {0, gy, h - gy, h};
        int[] su = {0, p.bx, p.w - p.bx, p.w}, sv = {0, p.by, p.h - p.by, p.h};
        begin(g, alpha);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int dw = dx[i + 1] - dx[i], dh = dy[j + 1] - dy[j];
                int uw = su[i + 1] - su[i], vh = sv[j + 1] - sv[j];
                if (dw <= 0 || dh <= 0 || uw <= 0 || vh <= 0) continue;
                // Corners are drawn as they are; the rest tiles when the theme asks (scanlines, paper grain).
                if (p.tile && (i == 1 || j == 1)) {
                    tiled(g, p, x + dx[i], y + dy[j], dw, dh, su[i], sv[j], uw, vh);
                } else {
                    g.blit(p.tex, x + dx[i], y + dy[j], dw, dh, su[i], sv[j], uw, vh, p.w, p.h);
                }
            }
        }
        end(g);
    }

    private void tiled(GuiGraphics g, Part p, int x, int y, int w, int h, int u, int v, int uw, int vh) {
        int stepX = Math.max(1, uw / scale), stepY = Math.max(1, vh / scale);
        for (int oy = 0; oy < h; oy += stepY) {
            int ch = Math.min(stepY, h - oy);
            for (int ox = 0; ox < w; ox += stepX) {
                int cw = Math.min(stepX, w - ox);
                g.blit(p.tex, x + ox, y + oy, cw, ch, u, v, cw * scale, ch * scale, p.w, p.h);
            }
        }
    }

    private static void begin(GuiGraphics g, float alpha) {
        RenderSystem.enableBlend();
        g.setColor(1F, 1F, 1F, Math.min(1F, alpha));
    }

    private static void end(GuiGraphics g) {
        g.setColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }
}

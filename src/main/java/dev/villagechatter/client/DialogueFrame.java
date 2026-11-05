package dev.villagechatter.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

/**
 * Where the dialogue window's pieces go and how they're drawn in a theme: a small centred box, the name on a tab over its
 * top edge, and the choices in a column to its right (stacked above it on a narrow screen), or, for themes that want it,
 * inside the box past a divider. The conversation ({@link DialogueScreen}) draws the scrolling text itself; the theme
 * preview uses this as is.
 */
final class DialogueFrame {
    // Speech box (GUI px)
    private static final float BOX_SHARE = 0.55F;
    private static final int BOX_MIN_WIDTH = 220, BOX_MAX_WIDTH = 340;
    static final int BOX_PAD_X = 14;
    static final int BOX_PAD_Y = 10;
    private static final int BOTTOM_MARGIN = 14;
    static final int LINE_HEIGHT = 10;
    private static final int MIN_LINES = 2;

    // Name tab
    private static final int TAB_H = 12, TAB_PAD_X = 7, TAB_INSET = 10;

    // Choices: a column to the right of the box, each as wide as its words
    static final int OPT_HEIGHT = 14;
    private static final int OPT_GAP = 3;
    private static final int OPT_PAD_X = 9;
    private static final int OPT_MAX_WIDTH = 160;
    private static final int COLUMN_GAP = 8;
    private static final int CURSOR_ROOM = 10;
    /** Inside the box (inline themes): padding around the choices' column. */
    private static final int INLINE_PAD = 5;

    final ThemeStyle theme;
    final List<String> lines;
    final int boxX, boxY, boxW, boxH;
    /** The text's column inside the box (the whole box, less the choices' column for inline themes). */
    private final int textW;
    private final int optX, optY;
    /** The divider's x (inline: inside the box; columnDivider: between box and choices), or -1. */
    private final int dividerX;
    private final int[] optW;
    private final List<Component> labels;

    DialogueFrame(Font font, int width, int height, ThemeStyle theme, String text, List<Component> options) {
        this.theme = theme;

        // Each choice as wide as its words, long ones cut short.
        optW = new int[options.size()];
        labels = new ArrayList<>();
        int widest = 0;
        Style style = theme.optionStyle();
        for (int i = 0; i < options.size(); i++) {
            Component label = options.get(i).copy().withStyle(s -> s.withFont(style.getFont()));
            if (font.width(label) > OPT_MAX_WIDTH - OPT_PAD_X * 2) {
                int room = OPT_MAX_WIDTH - OPT_PAD_X * 2 - font.width(Component.literal("…").withStyle(style));
                label = Component.literal(font.getSplitter().plainHeadByWidth(options.get(i).getString(), room, style) + "…").withStyle(style);
            }
            labels.add(label);
            optW[i] = Math.min(OPT_MAX_WIDTH, font.width(label) + OPT_PAD_X * 2);
            widest = Math.max(widest, optW[i]);
        }
        int columnH = options.size() * (OPT_HEIGHT + OPT_GAP) - OPT_GAP;
        int column = CURSOR_ROOM + widest + INLINE_PAD * 2;
        int share = Mth.clamp(Math.round(width * BOX_SHARE), BOX_MIN_WIDTH, BOX_MAX_WIDTH);

        // Inline themes hold the choices in the box, so it grows by their column; same width each, one boxed outline.
        boolean inline = theme.inline && !options.isEmpty() && share + column <= width - 24;
        boxW = inline ? share + column : Math.min(width - 24, share);
        textW = inline ? boxW - column : boxW;
        lines = font.getSplitter().splitLines(text, textW - BOX_PAD_X * 2, theme.textStyle())
                .stream().map(FormattedText::getString).toList();
        int rows = Math.max(MIN_LINES, lines.size()) * LINE_HEIGHT + BOX_PAD_Y * 2 - 2;
        boxH = inline ? Math.max(rows, columnH + INLINE_PAD * 2 + 4) : rows;
        int room = COLUMN_GAP + CURSOR_ROOM + widest;
        boolean beside = !inline && boxW + room <= width * 0.92F;
        // Centred; when the choices beside it would run off the screen, box and choices are centred together.
        int x = (width - boxW) / 2;
        if (beside && x + boxW + room > width - 8) x = Math.max((width - boxW - room) / 2, width - 8 - room - boxW);
        boxX = x;
        boxY = height - boxH - BOTTOM_MARGIN;

        if (inline) {
            for (int i = 0; i < optW.length; i++) optW[i] = widest;
            dividerX = boxX + textW;
            optX = dividerX + INLINE_PAD + CURSOR_ROOM;
            optY = boxY + (boxH - columnH) / 2;
        } else if (beside && boxX + boxW + room <= width - 8) {
            // Choices to the right of the box, the first level with its upper half.
            dividerX = theme.columnDivider ? boxX + boxW + COLUMN_GAP / 2 : -1;
            optX = boxX + boxW + COLUMN_GAP + CURSOR_ROOM;
            optY = Math.min(boxY + 4, height - 4 - columnH);
        } else {
            // Narrow screen: stack them above the box's right edge.
            dividerX = -1;
            optX = boxX + boxW - widest;
            optY = boxY - TAB_H / 2 - 6 - columnH;
        }
    }

    /** Where a line starts: the left of the text column, or centred in it. */
    int lineX(Font font, Component line) {
        return theme.center ? boxX + (textW - font.width(line)) / 2 : boxX + BOX_PAD_X;
    }

    int textY() {
        return boxY + BOX_PAD_Y + (theme.inline && boxH > lines.size() * LINE_HEIGHT + BOX_PAD_Y * 2
                ? (boxH - BOX_PAD_Y * 2 - Math.max(MIN_LINES, lines.size()) * LINE_HEIGHT) / 2 : 0);
    }

    int optionY(int i) {
        return optY + i * (OPT_HEIGHT + OPT_GAP);
    }

    int optionAt(double mx, double my) {
        for (int i = 0; i < optW.length; i++) {
            int oy = optionY(i);
            if (mx >= optX && mx < optX + optW[i] && my >= oy && my < oy + OPT_HEIGHT) return i;
        }
        return -1;
    }

    /** The box with its trimmings (a divider, Storybook's bell) and the speaker's name on its tab. */
    void paintBox(GuiGraphics g, Font font, Component title, float opacity) {
        ThemeStyle t = theme;
        t.nine(g, "box_fill", boxX, boxY, boxW, boxH, t.fill(opacity, t.boxAlpha));
        t.nine(g, "box_frame", boxX, boxY, boxW, boxH, t.frameAlpha);
        if (dividerX >= 0 && t.divider != 0) {
            boolean inside = dividerX < boxX + boxW;
            int top = inside ? boxY + 3 : boxY - 4, bottom = inside ? boxY + boxH - 3 : boxY + boxH + 2;
            g.fill(dividerX, top, dividerX + 1, bottom, (Math.round(t.frameAlpha * (inside ? 200 : 140)) << 24) | (t.divider & 0xFFFFFF));
        }
        if (t.has("emblem")) {
            t.sprite(g, "emblem", boxX + boxW / 2 - t.width("emblem") / 2, boxY + boxH - t.height("emblem") / 2 - 1, 1F);
        }

        Style nameStyle = t.nameStyle();
        String shown = t.nameUpper ? title.getString().toUpperCase(Locale.ROOT) : null;
        Component name = (shown != null ? Component.literal(shown) : title.copy()).withStyle(s -> s.withFont(nameStyle.getFont()));
        int icon = t.has("icon") ? t.width("icon") + 3 : t.tabIcon.isEmpty() ? 0 : 11;
        int tabW = font.width(name) + TAB_PAD_X * 2 + icon + (t.tabCenter ? 10 : 0);
        int tabX = t.tabCenter ? boxX + (textW - tabW) / 2 : boxX + TAB_INSET, tabY = boxY - TAB_H / 2;
        if (!t.tabBare) t.nine(g, "tab_fill", tabX, tabY, tabW, TAB_H, Math.min(1F, t.fill(opacity, t.boxAlpha) + 0.1F));
        t.nine(g, "tab_frame", tabX, tabY, tabW, TAB_H, t.frameAlpha);
        int nameX = tabX + TAB_PAD_X + icon + (t.tabCenter ? 5 : 0);
        if (t.has("icon")) t.sprite(g, "icon", tabX + TAB_PAD_X - 2, tabY + (TAB_H - t.height("icon")) / 2, 1F);
        if (!t.tabIcon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(tabX + TAB_PAD_X - 3, tabY + 2, 0);
            g.pose().scale(0.5F, 0.5F, 1F);
            g.renderItem(t.tabIcon, 0, 0);
            g.pose().popPose();
            if (t.divider != 0) g.fill(nameX - 4, tabY + 2, nameX - 3, tabY + TAB_H - 2, 0xB0000000 | (t.divider & 0xFFFFFF));
        }
        g.drawString(font, name, nameX, tabY + (TAB_H - 8) / 2 - (t.tabBare ? 1 : 0), t.name, t.shadow);
    }

    /**
     * The choices, faded in by {@code alpha} and slid {@code slide} px; the selected one outlined and pointed at.
     * {@code flash} (0..1) brightens the one being chosen.
     */
    void paintOptions(GuiGraphics g, Font font, int selected, int flashIndex, float flash, float alpha, int slide, float opacity, long now) {
        ThemeStyle t = theme;
        for (int i = 0; i < optW.length; i++) {
            int x = optX + slide;
            int oy = optionY(i);
            int w = optW[i];
            boolean sel = i == selected;
            t.nine(g, sel ? "sel_fill" : "opt_fill", x, oy, w, OPT_HEIGHT, alpha * t.fill(opacity, sel ? t.selAlpha : t.optAlpha));
            t.nine(g, "opt_frame", x, oy, w, OPT_HEIGHT, alpha * t.frameAlpha);
            if (sel) t.nine(g, "sel_frame", x, oy, w, OPT_HEIGHT, alpha * t.frameAlpha);
            if (i == flashIndex && flash > 0) {
                g.fill(x + 2, oy + 2, x + w - 2, oy + OPT_HEIGHT - 2, ((int) (flash * 0.55F * 255) << 24) | 0xFFFFFF);
            }
            if (alpha > 0.1F) {
                g.drawString(font, labels.get(i), x + OPT_PAD_X, oy + (OPT_HEIGHT - 8) / 2, fade(sel ? t.selected : t.option, alpha), t.shadow);
            }
            if (sel) {
                int bob = Math.round(Mth.sin(now / 160F) * 1.2F);
                t.sprite(g, "cursor", x - CURSOR_ROOM + bob, oy + (OPT_HEIGHT - t.height("cursor")) / 2, alpha);
            }
        }
    }

    static int fade(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * factor);
        return (Math.max(a, 4) << 24) | (argb & 0xFFFFFF);
    }
}

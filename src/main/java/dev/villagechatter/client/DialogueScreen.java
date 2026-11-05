package dev.villagechatter.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;


import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterSounds;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.network.ChatterNetwork.OpenDialogue;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The conversation window: a small centred see-through box with the speaker's name on a tab at its top-left edge, text
 * that scrolls in while the villager babbles (names of items and places in their own colour), and choices in a column to
 * the right, the selected one outlined and pointed at, fading in once the line is done. How it looks is the
 * dialogueTheme setting ({@link ThemeStyle}); how see-through, boxOpacity.
 */
public class DialogueScreen extends Screen {
    private static final int SHIMMER_COLOR = 0xFFF3E8FF;   // the glint sweeping over enchanted names

    // Timing
    private static final int FLASH_MS = 140;
    private static final int OPTIONS_FADE_MS = 160;
    private static final int COMMA_PAUSE_MS = 120;
    private static final int SENTENCE_PAUSE_MS = 260;

    /** An option id from the server (lang key villagechatter.option.&lt;id&gt;) and its label. */
    private record Option(Component label, String id) {}

    private final Entity speaker;
    private final OpenDialogue info;
    private final DialogueVoice voice;
    private final List<Option> options = new ArrayList<>();

    private String fullText = "";
    private String topic = "greeting";
    /** Indices in `flat` where a wrapped line starts (word boundaries the joined string hides). */
    private java.util.Set<Integer> lineStarts = java.util.Set.of();
    private List<String> lines = List.of();
    private String flat = "";
    /** Per character of {@code flat}: colour of an item or place name (0 = plain), and enchanted shimmer. */
    private KeywordHighlight.Marks marks = KeywordHighlight.Marks.none(0);
    private int revealed;
    private long nextCharAt;
    private long lineDoneAt = -1;

    private int selected;
    private int flashIndex = -1;
    private long flashStart;
    /** Set once we've handed off to the entity's own interaction, so closing doesn't also send goodbye. */
    private boolean handedOff;
    /** Whether the speaker says goodbye back when the window closes. Not when opening their trade menu. */
    private boolean sayFarewell = true;
    private boolean opened;
    /** Set while a child screen (gift picker) replaces this one; removed() then keeps the conversation going. */
    private boolean showingChild;

    /** Where everything goes, in the chosen theme ({@link DialogueFrame}). */
    private DialogueFrame frame;

    DialogueScreen(Entity speaker, OpenDialogue info, Component greeting) {
        super(speaker.getDisplayName());
        this.speaker = speaker;
        this.info = info;
        this.voice = new DialogueVoice(speaker, info.flags(), info.kind(), info.speakerPool());
        setOptions(info.options(), info.extras());
        fullText = greeting.getString();
    }

    int entityId() { return info.entityId(); }
    String speakerPool() { return info.speakerPool(); }
    int flags() { return info.flags(); }
    String kind() { return info.kind(); }
    Entity speaker() { return speaker; }

    /** Show a new line from the speaker: options hide, text scrolls in again. New options replace the old. */
    void say(Component line, String topic, List<String> newOptions, ChatterNetwork.Extras extras) {
        fullText = line.getString();
        this.topic = topic;
        if (!newOptions.isEmpty()) {
            setOptions(newOptions, extras);
            selected = 0;
        }
        voice.onLineStart(fullText, topic);
        layout();
    }

    /** This conversation's wording for each option (it varies between conversations, not within one). */
    private final java.util.Map<String, Component> wording = new java.util.HashMap<>();

    private void setOptions(List<String> ids, ChatterNetwork.Extras extras) {
        options.clear();
        for (String id : ids) {
            Component label = extras.labels().containsKey(id) ? extras.label(id)
                    : wording.computeIfAbsent(id, k -> OptionWording.pick(k, info.kind()));
            options.add(new Option(label, id));
        }
    }

    /** Golems and pets don't speak: their lines are narration. */
    private boolean narrated() {
        return info.kind().equals(ChatterNetwork.KIND_GOLEM) || info.kind().equals(ChatterNetwork.KIND_PET);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        layout();
        if (!opened) {
            opened = true;
            voice.onLineStart(fullText, topic);
            DialogueVoice.playUi(ChatterSounds.UI_OPEN.get(), 1.0F, 0.6F);
            DialogueCamera.start(speaker);
        }
    }

    private void layout() {
        frame = new DialogueFrame(font, width, height, ThemeStyle.current(), fullText, options.stream().map(Option::label).toList());
        lines = frame.lines;
        flat = String.join("", lines);
        java.util.Set<Integer> starts = new java.util.HashSet<>();
        int index = 0;
        for (String line : lines) {
            starts.add(index);
            index += line.length();
        }
        lineStarts = starts;
        marks = ChatterConfig.HIGHLIGHT_NAMES.get() && !narrated() ? KeywordHighlight.mask(flat) : KeywordHighlight.Marks.none(flat.length());
        revealed = ChatterConfig.TEXT_SPEED.get() == 0 ? flat.length() : 0;
        nextCharAt = Util.getMillis();
        lineDoneAt = revealed >= flat.length() ? Util.getMillis() : -1;
    }

    // ------------------------------------------------------------------ text scrolling

    private void advanceText(long now) {
        if (revealed >= flat.length()) return;
        int speed = ChatterConfig.TEXT_SPEED.get();
        if (speed == 0) {
            finishText();
            return;
        }
        // Don't let a long frame hitch dump a whole paragraph of babble at once.
        if (now - nextCharAt > 250) nextCharAt = now;
        while (revealed < flat.length() && now >= nextCharAt) {
            char c = flat.charAt(revealed++);
            voice.onCharRevealed(flat, revealed - 1, lineStarts.contains(revealed - 1), questionAhead(revealed));
            nextCharAt += 1000L / speed + pauseAfter(c, revealed);
        }
        if (revealed >= flat.length()) onLineDone(now);
    }

    private long pauseAfter(char c, int nextIndex) {
        boolean endOfWord = nextIndex >= flat.length() || flat.charAt(nextIndex) == ' ';
        if (!endOfWord) return 0;
        return switch (c) {
            case ',', ';', ':' -> COMMA_PAUSE_MS;
            case '.', '!', '?' -> SENTENCE_PAUSE_MS;
            default -> 0;
        };
    }

    private boolean questionAhead(int from) {
        for (int i = from; i < Math.min(flat.length(), from + 12); i++) {
            char c = flat.charAt(i);
            if (c == '?') return true;
            if (c == '.' || c == '!') return false;
        }
        return false;
    }

    private boolean isTyping() {
        return revealed < flat.length();
    }

    private void finishText() {
        if (!isTyping()) return;
        revealed = flat.length();
        onLineDone(Util.getMillis());
    }

    private void onLineDone(long now) {
        lineDoneAt = now;
        DialogueVoice.playUi(ChatterSounds.UI_ADVANCE.get(), 1.0F, 0.35F);
    }

    private float optionsAlpha(long now) {
        if (lineDoneAt < 0) return 0;
        return Mth.clamp((now - lineDoneAt) / (float) OPTIONS_FADE_MS, 0, 1);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        long now = Util.getMillis();
        advanceText(now);
        runPendingFlash(now);
        ThemeStyle t = frame.theme;
        float opacity = ChatterConfig.BOX_OPACITY.get().floatValue();
        frame.paintBox(g, font, title, opacity);

        int budget = revealed;
        int y = frame.textY();
        int index = 0;
        for (String line : lines) {
            if (budget <= 0) break;
            String shown = line.substring(0, Math.min(line.length(), budget));
            if (narrated()) {
                g.drawString(font, Component.literal(shown).withStyle(textStyle().withItalic(true)), frame.lineX(font, Component.literal(line).withStyle(textStyle())), y, t.narration, t.shadow);
            } else {
                drawHighlighted(g, shown, index, frame.lineX(font, Component.literal(line).withStyle(textStyle())), y);
            }
            budget -= line.length();
            index += line.length();
            y += DialogueFrame.LINE_HEIGHT;
        }

        // Choices fade + slide in once the line is done.
        float alpha = optionsAlpha(now);
        if (alpha <= 0) return;
        float flash = flashIndex < 0 ? 0 : 1 - Mth.clamp((now - flashStart) / (float) FLASH_MS, 0, 1);
        frame.paintOptions(g, font, selected, flashIndex, flash, alpha, Math.round((1 - alpha) * 6), opacity, now);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible (no menu blur), just a gentle fade behind the text.
        g.fillGradient(0, height * 2 / 3, width, height, 0x00000000, 0x50000000);
    }

    private int optionAt(double mx, double my) {
        return optionsAlpha(Util.getMillis()) <= 0 ? -1 : frame.optionAt(mx, my);
    }

    /**
     * Text with item and place names in their colour (rarity for items); enchanted names get a glint sweeping across
     * them letter by letter. {@code start} is where the text begins in {@code flat}.
     */
    private void drawHighlighted(GuiGraphics g, String text, int start, int x, int y) {
        float sweep = Util.getMillis() / 220F;
        int i = 0;
        while (i < text.length()) {
            int color = colorAt(start + i);
            boolean shimmer = shimmerAt(start + i);
            int j = i + 1;
            if (!shimmer) while (j < text.length() && colorAt(start + j) == color && !shimmerAt(start + j)) j++;
            Component part = Component.literal(text.substring(i, j)).withStyle(textStyle());
            int c = color == 0 ? frame.theme.text : color;
            if (shimmer) {
                float band = (float) Math.pow(Math.max(0, Mth.cos(sweep - (start + i) * 0.35F)), 6);
                c = lerpColor(c, SHIMMER_COLOR, band * 0.8F);
            }
            g.drawString(font, part, x, y, c, frame.theme.shadow);
            x += font.width(part);
            i = j;
        }
    }

    /** The speech font and style of the chosen theme (or the player's font choice). */
    private Style textStyle() {
        return (frame != null ? frame.theme : ThemeStyle.current()).textStyle();
    }

    private int colorAt(int index) {
        return index < marks.color().length ? marks.color()[index] : 0;
    }

    private boolean shimmerAt(int index) {
        return index < marks.shimmer().length && marks.shimmer()[index];
    }

    private static int lerpColor(int from, int to, float t) {
        int a = (int) Mth.lerp(t, (from >>> 24) & 0xFF, (to >>> 24) & 0xFF);
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int gr = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        int hovered = optionAt(mouseX, mouseY);
        if (hovered >= 0) select(hovered);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isTyping()) {
            finishText();
            return true;
        }
        int clicked = optionAt(mouseX, mouseY);
        if (clicked >= 0) {
            select(clicked);
            confirm(clicked);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isTyping() && scrollY != 0) select(Math.floorMod(selected + (scrollY > 0 ? -1 : 1), options.size()));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                if (!isTyping()) select(Math.floorMod(selected - 1, options.size()));
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                if (!isTyping()) select(Math.floorMod(selected + 1, options.size()));
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_E -> {
                if (isTyping()) finishText();
                else confirm(selected);
                return true;
            }
            default -> {}
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int index = keyCode - GLFW.GLFW_KEY_1;
            if (isTyping()) finishText();
            else if (index < options.size()) {
                select(index);
                confirm(index);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void select(int index) {
        if (index == selected || flashIndex >= 0) return;
        selected = index;
        DialogueVoice.playUi(ChatterSounds.UI_HOVER.get(), 1.0F, 0.5F);
    }

    private void confirm(int index) {
        if (flashIndex >= 0) return;
        flashIndex = index;
        flashStart = Util.getMillis();
        DialogueVoice.playUi(ChatterSounds.UI_SELECT.get(), 1.0F, 0.6F);
    }

    /** The choice runs after its flash, so the click reads before the screen changes. */
    private void runPendingFlash(long now) {
        if (flashIndex < 0 || now - flashStart < FLASH_MS) return;
        String id = options.get(flashIndex).id();
        flashIndex = -1;
        switch (id) {
            case DialogueAction.TRADE, DialogueAction.INTERACT -> {
                handedOff = true;
                sayFarewell = id.equals(DialogueAction.INTERACT);
                send(id);
                // The server opens the trade screen, replacing this one; close now in case it doesn't.
                onClose();
            }
            case DialogueAction.GOODBYE -> onClose();
            case dev.villagechatter.server.ConversationManager.GIFT -> {
                // The gift picker sits on top of the conversation, which carries on underneath (camera, server side).
                showingChild = true;
                minecraft.setScreen(new dev.villagechatter.reputation.client.GiftScreen(this, info.entityId(), speaker.getDisplayName()));
            }
            default -> {
                if (info.kind().equals(ChatterNetwork.KIND_PET) && dev.villagechatter.server.PetTalk.ENDS_CONVERSATION.contains(id)) {
                    // "Come on, let's go" / "Stay here": close now so the pet can actually do it; its reaction shows
                    // above the hotbar instead of in the box.
                    handedOff = true;
                    sayFarewell = false;
                    send(id);
                    onClose();
                    ClientDialogue.aside(speaker, info.speakerPool(), info.flags(), info.kind(), "reply." + id);
                    return;
                }
                send(id); // the server answers with a line (and maybe new options)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void tick() {
        // Client-side safety net in case the server's close packet never arrives.
        if (minecraft != null && minecraft.player != null
                && (!speaker.isAlive() || speaker.distanceToSqr(minecraft.player) > 16 * 16)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (showingChild) {
            // A child screen (the gift picker) took over for a moment: the conversation isn't over.
            showingChild = false;
            return;
        }
        DialogueCamera.stop();
        if (!handedOff) {
            send(DialogueAction.GOODBYE);
            DialogueVoice.playUi(ChatterSounds.UI_CLOSE.get(), 1.0F, 0.5F);
        }
        // Goodbye, Carry on or Esc: either way they say goodbye back.
        if (sayFarewell) ClientDialogue.farewell(speaker, info.speakerPool(), info.flags(), info.kind());
        super.removed();
    }

    /** Put another screen on top of the conversation (the job screen), which carries on underneath. */
    public void openChild(Screen child) {
        showingChild = true;
        minecraft.setScreen(child);
    }

    /** Close without telling the server (it already knows, e.g. you walked off). */
    public void closeQuietly() {
        handedOff = true;
        sayFarewell = false;
        onClose();
    }

    private void send(String action) {
        PacketDistributor.sendToServer(new DialogueAction(info.entityId(), action));
    }
}

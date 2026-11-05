package dev.villagechatter.quests.client;

import java.util.List;
import java.util.UUID;

import dev.villagechatter.quests.journal.QuestNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The quest journal: who asked, where they live, what's left to do. Give up on a quest (click twice to confirm),
 * or clear failed ones (struck through, with the reason) with the ×.
 */
public class JournalScreen extends Screen {
    private static final int PANEL_W = 340, ROW_H = 40, PAD = 10, HEADER = 40;
    private static final int PAPER = 0xF2E9DCB4, PAPER_EDGE = 0xFF6B4A2B, ROW_LINE = 0x40593B1E;
    private static final int INK = 0xFF3B2A1A, INK_SOFT = 0xFF6E5A44, GOLD = 0xFF8A5A00, RED = 0xFFA02020;

    private List<QuestNetwork.Entry> entries;
    /** "Villages: Liked (+23) · Pillagers: Wary (-31)", under the title; empty when factions are off. */
    private Component standing;
    private int scroll;
    /** Give up needs a second click on the same row. */
    private UUID confirming;

    private int left, top, panelH;

    public JournalScreen(List<QuestNetwork.Entry> entries, Component standing) {
        super(Component.translatable("villagequests.journal.title"));
        this.entries = entries;
        this.standing = standing;
    }

    /** Called for every journal packet: open the journal, or refresh it if it's already open. */
    public static void show(List<QuestNetwork.Entry> entries, Component standing) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof JournalScreen open) {
            open.entries = entries;
            open.standing = standing;
            open.confirming = null;
            open.scroll = Math.min(open.scroll, open.maxScroll());
        } else if (mc.screen == null) {
            mc.setScreen(new JournalScreen(entries, standing));
        }
    }

    @Override
    protected void init() {
        panelH = Math.min(height - 40, HEADER + PAD + Math.max(1, entries.size()) * ROW_H + PAD);
        left = (width - PANEL_W) / 2;
        top = (height - panelH) / 2;
    }

    private int visibleRows() {
        return Math.max(1, (panelH - HEADER - PAD * 2) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, entries.size() - visibleRows());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(left - 2, top - 2, left + PANEL_W + 2, top + panelH + 2, PAPER_EDGE);
        g.fill(left, top, left + PANEL_W, top + panelH, PAPER);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, top + 9, 0xFF4A2F14);
        if (!standing.getString().isEmpty()) {
            g.drawString(font, standing, (width - font.width(standing)) / 2, top + 22, INK_SOFT, false);
        }
        g.fill(left + PAD, top + HEADER - 4, left + PANEL_W - PAD, top + HEADER - 3, ROW_LINE);

        if (entries.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("villagequests.journal.empty"), width / 2, top + HEADER + 14, INK_SOFT);
            return;
        }
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < entries.size(); i++) {
            QuestNetwork.Entry e = entries.get(i + scroll);
            int y = top + HEADER + PAD + i * ROW_H;
            int x = left + PAD;
            int textW = PANEL_W - PAD * 2 - 60;

            Component title = e.failed()
                    ? e.title().copy().withStyle(ChatFormatting.STRIKETHROUGH)
                    : e.title();
            g.drawString(font, font.substrByWidth(title, textW).getString(), x, y, e.failed() ? RED : GOLD, false);
            Component who = Component.empty().append(e.giver()).append(" · ").append(e.location());
            g.drawString(font, font.substrByWidth(who, textW).getString(), x, y + 11, INK_SOFT, false);
            g.drawString(font, font.substrByWidth(e.status(), textW + 50).getString(), x, y + 22, e.failed() ? RED : INK, false);

            Component button = button(e);
            int bw = font.width(button) + 8, bx = left + PANEL_W - PAD - bw, by = y;
            boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 12;
            g.fill(bx, by, bx + bw, by + 12, hover ? 0x60593B1E : 0x30593B1E);
            g.drawString(font, button, bx + 4, by + 2, e.failed() ? RED : INK, false);
            if (i < rows - 1) g.fill(x, y + ROW_H - 4, left + PANEL_W - PAD, y + ROW_H - 3, ROW_LINE);
        }
        if (maxScroll() > 0) {
            g.drawString(font, (scroll + 1) + "–" + Math.min(entries.size(), scroll + rows) + " / " + entries.size(),
                    left + PANEL_W - PAD - 40, top + 9, INK_SOFT, false);
        }
    }

    private Component button(QuestNetwork.Entry e) {
        if (e.failed()) return Component.literal("×");
        return Component.translatable(e.instance().equals(confirming) ? "villagequests.journal.confirm" : "villagequests.journal.quit");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < entries.size(); i++) {
            QuestNetwork.Entry e = entries.get(i + scroll);
            int y = top + HEADER + PAD + i * ROW_H;
            int bw = font.width(button(e)) + 8, bx = left + PANEL_W - PAD - bw;
            if (mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + 12) {
                click();
                if (e.failed()) {
                    PacketDistributor.sendToServer(new QuestNetwork.QuestAction(e.instance(), QuestNetwork.QuestAction.DISMISS));
                } else if (e.instance().equals(confirming)) {
                    PacketDistributor.sendToServer(new QuestNetwork.QuestAction(e.instance(), QuestNetwork.QuestAction.QUIT));
                } else {
                    confirming = e.instance();
                }
                return true;
            }
        }
        confirming = null;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (QuestsClient.OPEN_JOURNAL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

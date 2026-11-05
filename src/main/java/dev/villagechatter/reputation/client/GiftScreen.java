package dev.villagechatter.reputation.client;

import dev.villagechatter.reputation.StandingNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "I'd like to give you a gift": your inventory laid out as a picker (not the real inventory screen). Click an item
 * to choose it, then Give it or change your mind. You go back to the conversation either way, and the villager
 * reacts there. Nothing hints at what they'd like: that's for you to figure out.
 */
public class GiftScreen extends Screen {
    private static final int COLS = 9, SLOT = 20, PAD = 10;
    private static final int PAPER = 0xF2E9DCB4, EDGE = 0xFF6B4A2B, INK = 0xFF3B2A1A, INK_SOFT = 0xFF6E5A44;

    private final Screen dialogue;
    private final int villagerId;
    private final Component villagerName;
    private int selected = -1;
    private int left, top, w, h;

    public GiftScreen(Screen dialogue, int villagerId, Component villagerName) {
        super(Component.translatable("villagequests.gift.screen.title", villagerName));
        this.dialogue = dialogue;
        this.villagerId = villagerId;
        this.villagerName = villagerName;
    }

    @Override
    protected void init() {
        w = COLS * SLOT + PAD * 2;
        h = 4 * SLOT + PAD * 2 + 54;
        left = (width - w) / 2;
        top = (height - h) / 2;
    }

    /** Inventory slot shown at grid cell i: hotbar last, like the real inventory. */
    private static int slotAt(int i) {
        return i < 27 ? i + 9 : i - 27;
    }

    private int cellX(int i) { return left + PAD + (i % COLS) * SLOT; }

    private int cellY(int i) { return top + PAD + 14 + (i / COLS) * SLOT + (i >= 27 ? 4 : 0); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Inventory inv = Minecraft.getInstance().player.getInventory();
        g.fill(left - 2, top - 2, left + w + 2, top + h + 2, EDGE);
        g.fill(left, top, left + w, top + h, PAPER);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, top + 6, 0xFF4A2F14);

        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < 36; i++) {
            int x = cellX(i), y = cellY(i);
            ItemStack stack = inv.getItem(slotAt(i));
            boolean hover = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
            g.fill(x, y, x + 18, y + 18, i == selected ? 0xC0D8A040 : hover ? 0x60593B1E : 0x30593B1E);
            if (!stack.isEmpty()) {
                g.renderItem(stack, x + 1, y + 1);
                g.renderItemDecorations(font, stack, x + 1, y + 1);
                if (hover) hovered = stack;
            }
        }

        int by = top + h - 44;
        ItemStack chosen = selected >= 0 ? inv.getItem(slotAt(selected)) : ItemStack.EMPTY;
        Component prompt = chosen.isEmpty()
                ? Component.translatable("villagequests.gift.screen.pick")
                : Component.translatable("villagequests.gift.screen.confirm", chosen.getHoverName(), villagerName);
        g.drawCenteredString(font, font.substrByWidth(prompt, w - 12).getString(), width / 2, by, chosen.isEmpty() ? INK_SOFT : INK);

        drawButton(g, giveX(), by + 16, Component.translatable("villagequests.gift.screen.give"), !chosen.isEmpty(), mouseX, mouseY);
        drawButton(g, cancelX(), by + 16, Component.translatable("villagequests.gift.screen.cancel"), true, mouseX, mouseY);

        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private int giveX() { return width / 2 - 60; }

    private int cancelX() { return width / 2 + 6; }

    private void drawButton(GuiGraphics g, int x, int y, Component label, boolean active, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + 54 && mouseY >= y && mouseY < y + 16;
        g.fill(x, y, x + 54, y + 16, !active ? 0x18593B1E : hover ? 0x70593B1E : 0x38593B1E);
        g.drawCenteredString(font, label, x + 27, y + 4, active ? INK : 0x80593B1E);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < 36; i++) {
            int x = cellX(i), y = cellY(i);
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                if (!inv.getItem(slotAt(i)).isEmpty()) {
                    selected = i;
                    click();
                }
                return true;
            }
        }
        int by = top + h - 44 + 16;
        if (mouseY >= by && mouseY < by + 16) {
            if (mouseX >= giveX() && mouseX < giveX() + 54 && selected >= 0 && !inv.getItem(slotAt(selected)).isEmpty()) {
                click();
                PacketDistributor.sendToServer(new StandingNetwork.GiveGift(villagerId, slotAt(selected)));
                back();
                return true;
            }
            if (mouseX >= cancelX() && mouseX < cancelX() + 54) {
                click();
                back();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Esc goes back to the conversation too. */
    @Override
    public void onClose() {
        back();
    }

    private void back() {
        Minecraft.getInstance().setScreen(dialogue);
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.5F));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

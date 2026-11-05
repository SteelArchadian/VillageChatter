package dev.villagechatter.life.job.client;

import java.util.List;

import dev.villagechatter.client.DialogueScreen;
import dev.villagechatter.life.job.JobNetwork;
import dev.villagechatter.life.job.Jobs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "About your job": every profession (modded ones too) with its job block, on the same paper as the gift picker.
 * Pick one to see the matching workstations nearby (distance, direction, who works there); click one to assign it,
 * or "Pick workstation" to choose one out in the world. Kids and nitwits can't take any job, and villagers who've
 * traded start over (or keep their job, if the server says so).
 */
public class JobScreen extends Screen {
    private static final int PAPER = 0xF2E9DCB4, EDGE = 0xFF6B4A2B, INK = 0xFF3B2A1A, INK_SOFT = 0xFF6E5A44,
            INK_FADED = 0x80593B1E, WARN = 0xFF9A3B12, GOOD = 0xFF3F6B22;
    private static final int ROW = 20, SITE_ROW = 12, MAX_SITES = 6;

    private final DialogueScreen dialogue;
    private final Entity villager;
    private final JobNetwork.JobInfo info;
    private final List<VillagerProfession> jobs = Jobs.assignable();
    private final boolean canWork;
    private int selected;
    private int scroll;
    private int left, top, w, h, listW, visibleRows;

    public JobScreen(DialogueScreen dialogue, Entity villager, JobNetwork.JobInfo info) {
        super(Component.translatable("villagechatter.job.title", villager.getDisplayName()));
        this.dialogue = dialogue;
        this.villager = villager;
        this.info = info;
        this.canWork = !info.has(JobNetwork.FLAG_KID) && !info.has(JobNetwork.FLAG_NITWIT);
        int current = -1;
        for (int i = 0; i < jobs.size(); i++) if (Jobs.id(jobs.get(i)).toString().equals(info.current())) current = i;
        this.selected = Math.max(0, current);
    }

    @Override
    protected void init() {
        w = Math.min(340, width - 20);
        h = Math.min(230, height - 20);
        left = (width - w) / 2;
        top = (height - h) / 2;
        listW = Math.min(130, w / 2 - 10);
        visibleRows = Math.max(3, (h - 64) / ROW);
        scroll = Mth.clamp(selected - visibleRows / 2, 0, Math.max(0, jobs.size() - visibleRows));
    }

    private VillagerProfession job() {
        return jobs.isEmpty() ? VillagerProfession.NONE : jobs.get(selected);
    }

    private boolean isCurrent(VillagerProfession job) {
        return Jobs.id(job).toString().equals(info.current());
    }

    /** Whether this job can be given: kids, nitwits and locked veterans can't change. */
    private boolean allowed(VillagerProfession job) {
        return canWork && (isCurrent(job) || !info.has(JobNetwork.FLAG_TRADED_LOCKED));
    }

    private int listX() { return left + 10; }

    private int listY() { return top + 34; }

    private int detailX() { return left + 20 + listW; }

    private int detailW() { return left + w - 10 - detailX(); }

    private int buttonY() { return top + h - 24; }

    private int pickX() { return left + w - 10 - 150; }

    private int backX() { return left + w - 10 - 60; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(left - 2, top - 2, left + w + 2, top + h + 2, EDGE);
        g.fill(left, top, left + w, top + h, PAPER);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, top + 7, 0xFF4A2F14);
        VillagerProfession now = Jobs.byId(info.current());
        Component sub = now == null || now == VillagerProfession.NONE ? Component.translatable("villagechatter.job.none")
                : now == VillagerProfession.NITWIT ? Component.translatable("villagechatter.job.nitwit")
                : Component.translatable("villagechatter.job.current", Jobs.name(now));
        g.drawCenteredString(font, sub, width / 2, top + 19, INK_SOFT);

        renderList(g, mouseX, mouseY);
        renderDetail(g, mouseX, mouseY);

        VillagerProfession job = job();
        boolean pickable = allowed(job);
        drawButton(g, pickX(), buttonY(), 84, Component.translatable("villagechatter.job.pick"), pickable, mouseX, mouseY);
        drawButton(g, backX(), buttonY(), 60, Component.translatable("villagechatter.job.back"), true, mouseX, mouseY);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int x = listX(), y = listY();
        g.fill(x - 2, y - 2, x + listW + 2, y + visibleRows * ROW + 2, 0x30593B1E);
        g.enableScissor(x, y, x + listW, y + visibleRows * ROW);
        for (int i = scroll; i < Math.min(jobs.size(), scroll + visibleRows); i++) {
            VillagerProfession job = jobs.get(i);
            int ry = y + (i - scroll) * ROW;
            boolean hover = mouseX >= x && mouseX < x + listW && mouseY >= ry && mouseY < ry + ROW;
            if (i == selected) g.fill(x, ry, x + listW, ry + ROW, 0xC0D8A040);
            else if (hover) g.fill(x, ry, x + listW, ry + ROW, 0x50593B1E);
            g.renderItem(Jobs.icon(job), x + 2, ry + 2);
            int color = allowed(job) ? INK : INK_FADED;
            String name = font.plainSubstrByWidth(Jobs.name(job).getString(), listW - 34);
            g.drawString(font, name, x + 22, ry + 6, color, false);
            if (isCurrent(job)) g.drawString(font, "✓", x + listW - 10, ry + 6, GOOD, false);
        }
        g.disableScissor();
        if (jobs.size() > visibleRows) {
            // A thin scroll bar along the list.
            int barH = Math.max(10, visibleRows * ROW * visibleRows / jobs.size());
            int barY = y + (visibleRows * ROW - barH) * scroll / Math.max(1, jobs.size() - visibleRows);
            g.fill(x + listW, barY, x + listW + 2, barY + barH, 0xA06B4A2B);
        }
    }

    private void renderDetail(GuiGraphics g, int mouseX, int mouseY) {
        VillagerProfession job = job();
        int x = detailX(), y = listY(), dw = detailW();
        // The job block, large.
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.renderItem(Jobs.icon(job), 0, 0);
        g.pose().popPose();
        g.drawString(font, Jobs.name(job).copy().withStyle(ChatFormatting.BOLD), x + 38, y + 4, INK, false);
        g.drawString(font, Jobs.blockName(job), x + 38, y + 18, INK_SOFT, false);

        int ty = y + 40;
        Component note = note(job);
        if (note != null) {
            for (FormattedCharSequence line : font.split(note, dw)) {
                g.drawString(font, line, x, ty, WARN, false);
                ty += 10;
            }
            ty += 4;
        }

        List<JobNetwork.Site> sites = ClientJobs.sitesFor(info, job);
        g.drawString(font, Component.translatable("villagechatter.job.nearby", Jobs.blockName(job)), x, ty, INK, false);
        ty += 12;
        if (sites.isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.translatable("villagechatter.job.no_sites", Jobs.blockName(job), info.radius()), dw)) {
                g.drawString(font, line, x, ty, INK_SOFT, false);
                ty += 10;
            }
            return;
        }
        boolean clickable = allowed(job);
        for (int i = 0; i < Math.min(MAX_SITES, sites.size()); i++) {
            JobNetwork.Site site = sites.get(i);
            int ry = ty + i * SITE_ROW;
            boolean hover = clickable && !site.theirs() && mouseX >= x && mouseX < x + dw && mouseY >= ry - 1 && mouseY < ry + SITE_ROW - 1;
            if (hover) g.fill(x - 2, ry - 2, x + dw, ry + SITE_ROW - 2, 0x50593B1E);
            Component where = ClientJobs.where(villager, site.pos());
            int whereW = font.width(where);
            g.drawString(font, where, x, ry, INK, false);
            Component status = ClientJobs.status(site);
            String text = font.plainSubstrByWidth(status.getString(), dw - whereW - 8);
            g.drawString(font, text, x + dw - font.width(text) - 2, ry, site.theirs() || site.free() ? GOOD : INK_SOFT, false);
        }
        if (sites.size() > MAX_SITES) {
            g.drawString(font, Component.translatable("villagechatter.job.more", sites.size() - MAX_SITES), x, ty + MAX_SITES * SITE_ROW, INK_SOFT, false);
        }
    }

    /** Why this job can't be given, or what giving it costs. */
    private Component note(VillagerProfession job) {
        Component name = villager.getDisplayName();
        if (info.has(JobNetwork.FLAG_KID)) return Component.translatable("villagechatter.job.kid_cant", name);
        if (info.has(JobNetwork.FLAG_NITWIT)) return Component.translatable("villagechatter.job.nitwit_cant", name);
        if (isCurrent(job)) return null;
        if (info.has(JobNetwork.FLAG_TRADED_LOCKED)) return Component.translatable("villagechatter.job.traded_locked", name);
        if (info.has(JobNetwork.FLAG_TRADED)) return Component.translatable("villagechatter.job.traded_warn", name);
        return null;
    }

    private void drawButton(GuiGraphics g, int x, int y, int bw, Component label, boolean active, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + 16;
        g.fill(x, y, x + bw, y + 16, !active ? 0x18593B1E : hover ? 0x70593B1E : 0x38593B1E);
        g.drawCenteredString(font, label, x + bw / 2, y + 4, active ? INK : INK_FADED);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (jobs.size() > visibleRows) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, jobs.size() - visibleRows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = listX(), y = listY();
        if (mouseX >= x && mouseX < x + listW && mouseY >= y && mouseY < y + visibleRows * ROW) {
            int i = scroll + (int) ((mouseY - y) / ROW);
            if (i < jobs.size()) {
                selected = i;
                click();
            }
            return true;
        }
        VillagerProfession job = job();
        if (mouseY >= buttonY() && mouseY < buttonY() + 16) {
            if (mouseX >= pickX() && mouseX < pickX() + 84 && allowed(job)) {
                click();
                startPicking(job);
                return true;
            }
            if (mouseX >= backX() && mouseX < backX() + 60) {
                click();
                back();
                return true;
            }
        }
        JobNetwork.Site site = siteAt(mouseX, mouseY);
        if (site != null && allowed(job)) {
            click();
            confirm(job, site);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private JobNetwork.Site siteAt(double mouseX, double mouseY) {
        VillagerProfession job = job();
        List<JobNetwork.Site> sites = ClientJobs.sitesFor(info, job);
        if (sites.isEmpty() || mouseX < detailX() || mouseX >= detailX() + detailW()) return null;
        int ty = listY() + 40;
        Component note = note(job);
        if (note != null) ty += font.split(note, detailW()).size() * 10 + 4;
        ty += 12;
        int i = (int) Math.floor((mouseY - ty + 1) / SITE_ROW);
        if (mouseY < ty - 1 || i < 0 || i >= Math.min(MAX_SITES, sites.size())) return null;
        JobNetwork.Site site = sites.get(i);
        return site.theirs() ? null : site;
    }

    private void confirm(VillagerProfession job, JobNetwork.Site site) {
        Minecraft mc = Minecraft.getInstance();
        Component name = villager.getDisplayName();
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                // Back to the conversation first: the villager answers there.
                mc.setScreen(dialogue);
                PacketDistributor.sendToServer(new JobNetwork.JobRequest(info.entityId(), JobNetwork.ASSIGN, Jobs.id(job).toString(), site.pos()));
            } else {
                mc.setScreen(this);
            }
        }, Component.translatable("villagechatter.job.confirm.title", Jobs.blockName(job), name),
                ClientJobs.confirmMessage(name, job, site, info)));
    }

    /** The talk closes and the villager waits while you choose a workstation out in the world. */
    private void startPicking(VillagerProfession job) {
        Minecraft mc = Minecraft.getInstance();
        PacketDistributor.sendToServer(new JobNetwork.JobRequest(info.entityId(), JobNetwork.PICK, Jobs.id(job).toString(), net.minecraft.core.BlockPos.ZERO));
        mc.setScreen(dialogue);
        dialogue.closeQuietly();
        JobPick.start(villager, job, info);
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

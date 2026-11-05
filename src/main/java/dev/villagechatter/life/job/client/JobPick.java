package dev.villagechatter.life.job.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.villagechatter.life.job.JobNetwork;
import dev.villagechatter.life.job.Jobs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picking a villager's workstation out in the world. Matching job blocks nearby are outlined (green when free,
 * amber when someone works there), the one under the crosshair is labelled, and right-clicking it asks to confirm.
 * It ends when you switch hotbar slot, get hurt, press Esc, walk away from the villager, or after the time limit.
 */
public final class JobPick {
    private JobPick() {}

    private static Entity villager;
    private static VillagerProfession job;
    private static JobNetwork.JobInfo info;
    private static final Map<BlockPos, JobNetwork.Site> SITES = new HashMap<>();
    private static final Set<BlockState> STATES = new HashSet<>();
    private static long endsAt;
    private static int slot;
    private static float health;
    private static int ticks;

    public static boolean active() {
        return villager != null;
    }

    static void start(Entity who, VillagerProfession profession, JobNetwork.JobInfo jobInfo) {
        Minecraft mc = Minecraft.getInstance();
        villager = who;
        job = profession;
        info = jobInfo;
        SITES.clear();
        for (JobNetwork.Site site : ClientJobs.sitesFor(jobInfo, profession)) SITES.put(site.pos(), site);
        STATES.clear();
        for (Holder<PoiType> poi : Jobs.poiTypes(profession)) STATES.addAll(poi.value().matchingStates());
        endsAt = System.currentTimeMillis() + jobInfo.seconds() * 1000L;
        slot = mc.player.getInventory().selected;
        health = mc.player.getHealth();
        ticks = 0;
        hint();
    }

    /** Stop picking; tells the server so the villager can get on with its day. */
    static void stop(Component why) {
        if (villager == null) return;
        PacketDistributor.sendToServer(new JobNetwork.JobRequest(villager.getId(), JobNetwork.CANCEL, "", BlockPos.ZERO));
        villager = null;
        if (why != null) Minecraft.getInstance().gui.setOverlayMessage(why, false);
    }

    private static void hint() {
        int left = (int) Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000);
        Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("villagechatter.job.pick.hint",
                Jobs.blockName(job), villager.getDisplayName(), left), false);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (villager == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            villager = null;
            return;
        }
        if (mc.screen instanceof ConfirmScreen) return; // deciding: don't run out from under the question
        Component stopped = Component.translatable("villagechatter.job.pick.stopped");
        if (!villager.isAlive() || villager.isRemoved() || villager.level() != mc.level) stop(stopped);
        else if (System.currentTimeMillis() > endsAt) stop(Component.translatable("villagechatter.job.pick.timeout"));
        else if (mc.player.getInventory().selected != slot) stop(stopped);
        else if (mc.player.getHealth() < health) stop(Component.translatable("villagechatter.job.pick.hurt"));
        else if (villager.distanceToSqr(mc.player) > (double) info.radius() * info.radius()) stop(Component.translatable("villagechatter.job.pick.too_far", villager.getDisplayName()));
        else {
            health = mc.player.getHealth();
            if (++ticks % 20 == 0) hint();
        }
    }

    /** The block under the crosshair, if it's one of this job's workstations. */
    private static BlockPos target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK || mc.level == null) return null;
        BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        return STATES.contains(mc.level.getBlockState(pos)) ? pos : null;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (villager == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        BlockPos aimed = target();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (JobNetwork.Site site : SITES.values()) {
            if (!STATES.contains(mc.level.getBlockState(site.pos()))) continue; // broken since the list was made
            AABB box = new AABB(site.pos()).inflate(0.004);
            if (site.pos().equals(aimed)) LevelRenderer.renderLineBox(pose, lines, box, 1.0F, 0.85F, 0.2F, 1.0F);
            else if (site.free() || site.theirs()) LevelRenderer.renderLineBox(pose, lines, box, 0.35F, 0.95F, 0.35F, 0.9F);
            else LevelRenderer.renderLineBox(pose, lines, box, 1.0F, 0.55F, 0.15F, 0.9F);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** A small tag under the crosshair: the block, and whether it's free. */
    public static void onRenderGui(RenderGuiEvent.Post event) {
        BlockPos pos = villager == null ? null : target();
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.screen != null) return;
        GuiGraphics g = event.getGuiGraphics();
        JobNetwork.Site site = SITES.get(pos);
        Component status = site == null ? Component.translatable("villagechatter.job.site.free") : ClientJobs.status(site);
        Component name = Jobs.blockName(job);
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 12;
        int tw = Math.max(mc.font.width(name), mc.font.width(status)) + 8;
        g.fill(cx - tw / 2, y - 2, cx + tw / 2, y + 20, 0xA0000000);
        g.drawCenteredString(mc.font, name, cx, y, 0xFFFFE08A);
        boolean free = site == null || site.free() || site.theirs();
        g.drawCenteredString(mc.font, status, cx, y + 10, free ? 0xFF8AE07A : 0xFFFFB060);
    }

    /** Right-click: confirm this block (and nothing else happens: no placing, no opening). */
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (villager == null || !event.isUseItem()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = target();
        if (pos == null) {
            mc.gui.setOverlayMessage(Component.translatable("villagechatter.job.pick.wrong", Jobs.blockName(job)), false);
            return;
        }
        JobNetwork.Site site = SITES.get(pos);
        if (site != null && site.theirs()) {
            mc.gui.setOverlayMessage(Component.translatable("villagechatter.job.site.theirs"), false);
            return;
        }
        Entity who = villager;
        VillagerProfession picked = job;
        JobNetwork.JobInfo jobInfo = info;
        Component name = who.getDisplayName();
        mc.setScreen(new ConfirmScreen(yes -> {
            mc.setScreen(null);
            if (yes && villager == who) {
                PacketDistributor.sendToServer(new JobNetwork.JobRequest(who.getId(), JobNetwork.ASSIGN, Jobs.id(picked).toString(), pos));
                villager = null; // the server ends its side when it assigns
            }
        }, Component.translatable("villagechatter.job.confirm.title", Jobs.blockName(picked), name),
                ClientJobs.confirmMessage(name, picked, site, jobInfo)));
    }

    /** Esc stops picking instead of opening the pause menu. */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (villager != null && event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            stop(Component.translatable("villagechatter.job.pick.stopped"));
        }
    }
}

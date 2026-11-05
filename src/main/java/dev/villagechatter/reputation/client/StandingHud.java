package dev.villagechatter.reputation.client;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.VillageChatter;
import dev.villagechatter.reputation.StandingNetwork;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A tiny readout under the crosshair while you look at a villager (your standing with them) or a village bell
 * (with the whole village): a colored title and a small line under it. No box, fades in and out.
 */
public final class StandingHud {
    private StandingHud() {}

    /** Re-ask the server this often while looking at the same thing, so changes show up. */
    private static final int REFRESH_TICKS = 20;

    private static int lookKind = -1, lookEntity = -1;
    private static BlockPos lookPos = BlockPos.ZERO;
    private static int sinceRequest;
    private static StandingNetwork.StandingInfo shown;
    /** 0..1 */
    private static float alpha;

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, VillageChatter.id("standing"), StandingHud::render);
    }

    public static void receive(StandingNetwork.StandingInfo info) {
        boolean byPos = info.kind() == StandingNetwork.BELL || info.kind() == StandingNetwork.BANNER;
        if (info.kind() == lookKind && (byPos ? info.pos().equals(lookPos) : info.entityId() == lookEntity)) {
            shown = info;
        }
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean looking = false;
        if (mc.player != null && mc.level != null && mc.screen == null && ChatterConfig.REPUTATION_HUD.get()) {
            HitResult hit = mc.hitResult;
            int kind = -1, entity = -1;
            BlockPos pos = BlockPos.ZERO;
            if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof Villager v) {
                kind = StandingNetwork.VILLAGER;
                entity = v.getId();
            } else if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof net.minecraft.world.entity.monster.AbstractIllager i) {
                kind = StandingNetwork.PILLAGER;
                entity = i.getId();
            } else if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin p) {
                kind = StandingNetwork.PIGLIN;
                entity = p.getId();
            } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockState(bh.getBlockPos()).is(Blocks.BELL)) {
                kind = StandingNetwork.BELL;
                pos = bh.getBlockPos();
            } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockState(bh.getBlockPos()).is(net.minecraft.tags.BlockTags.BANNERS)) {
                kind = StandingNetwork.BANNER; // the server only answers for an ominous (pillager) banner
                pos = bh.getBlockPos();
            }
            if (kind >= 0) {
                looking = true;
                boolean changed = kind != lookKind || entity != lookEntity || !pos.equals(lookPos);
                if (changed) {
                    lookKind = kind;
                    lookEntity = entity;
                    lookPos = pos;
                    shown = null;
                }
                if (changed || ++sinceRequest >= REFRESH_TICKS) {
                    sinceRequest = 0;
                    if (mc.getConnection() != null && mc.getConnection().hasChannel(StandingNetwork.LookAt.TYPE)) {
                        PacketDistributor.sendToServer(new StandingNetwork.LookAt(kind, entity, pos));
                    }
                }
            }
        }
        if (!looking) {
            lookKind = -1;
            lookEntity = -1;
        }
        float target = looking && shown != null ? 1F : 0F;
        alpha = Mth.approach(alpha, target, looking ? 0.25F : 0.15F);
        if (alpha <= 0F) shown = null;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        StandingNetwork.StandingInfo info = shown;
        if (info == null || alpha <= 0.02F || mc.options.hideGui) return;
        Font font = mc.font;
        int a = Mth.clamp((int) (alpha * 255), 8, 255) << 24;
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 9;

        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(0.8F, 0.8F, 1F);
        g.drawString(font, info.title(), -font.width(info.title()) / 2, 0, a | info.color(), true);
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(cx, y + 9, 0);
        g.pose().scale(0.6F, 0.6F, 1F);
        g.drawString(font, info.detail(), -font.width(info.detail()) / 2, 0, a | 0xC8C0B0, true);
        g.pose().popPose();
    }
}

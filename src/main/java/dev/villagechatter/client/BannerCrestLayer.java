package dev.villagechatter.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.villagechatter.reputation.faction.BannerLoyalty;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Draws the ominous banner above your head, where a captain wears his (Factions v2, item 5): on top of a crested helmet,
 * or when it's in a Curios head slot. (On the head slot itself vanilla's head layer already draws it, as for captains.)
 */
public final class BannerCrestLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final ItemInHandRenderer items;
    private ItemStack banner = ItemStack.EMPTY;
    private net.minecraft.client.multiplayer.ClientLevel bannerLevel;

    BannerCrestLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, ItemInHandRenderer items) {
        super(parent);
        this.items = items;
    }

    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model model : event.getSkins()) {
            if (event.getSkin(model) instanceof PlayerRenderer renderer) {
                renderer.addLayer(new BannerCrestLayer(renderer, event.getContext().getItemInHandRenderer()));
            }
        }
    }

    static void onTooltip(ItemTooltipEvent event) {
        if (BannerLoyalty.hasCrest(event.getItemStack())) {
            event.getToolTip().add(1, Component.translatable("item.villagechatter.ominous_crest").withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
            float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        boolean crest = BannerLoyalty.hasCrest(head);
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!crest && (!head.isEmpty() || !BannerLoyalty.curiosHead(player,
                s -> s.getItem() instanceof net.minecraft.world.item.BannerItem && BannerLoyalty.isOminous(s, level.registryAccess())))) {
            return;
        }
        if (banner.isEmpty() || bannerLevel != level) {
            bannerLevel = level;
            banner = Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN));
        }
        pose.pushPose();
        getParentModel().getHead().translateAndRotate(pose);
        // As a head-slot block item is drawn (vanilla's head layer, as on a captain).
        pose.translate(0.0F, -0.25F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.scale(0.625F, -0.625F, -0.625F);
        if (crest) pose.scale(1.12F, 1.12F, 1.12F); // a little larger so it clears the helmet
        items.renderItem(player, banner, ItemDisplayContext.HEAD, false, pose, buffers, light);
        pose.popPose();
    }
}

package dev.villagechatter.client;

import dev.villagechatter.VillageChatter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = VillageChatter.MODID, dist = Dist.CLIENT)
public class VillageChatterClient {
    public VillageChatterClient(IEventBus modEventBus, ModContainer container) {
        // Config button in the mod list: our shared Config Panel screen when it's installed, NeoForge's otherwise.
        if (net.neoforged.fml.ModList.get().isLoaded("configpanel")) dev.configpanel.ConfigPanel.register(container);
        else container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        NeoForge.EVENT_BUS.addListener(DialogueCamera::onComputeFov);
        modEventBus.addListener(BannerCrestLayer::onAddLayers);
        NeoForge.EVENT_BUS.addListener(BannerCrestLayer::onTooltip);
        NeoForge.EVENT_BUS.addListener(DialogueCamera::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(DialogueCamera::onClientTick);
        NeoForge.EVENT_BUS.addListener(DialogueCamera::onRenderGui);
        NeoForge.EVENT_BUS.addListener(DialogueBlur::onRenderLevel);
        modEventBus.addListener(DialogueBlur::onRegisterReloadListeners);
        modEventBus.addListener(ThemeStyle::onRegisterReloadListeners);
        NeoForge.EVENT_BUS.addListener(ThemePreview::onScreenOpening);
        modEventBus.addListener(dev.villagechatter.reputation.client.StandingHud::registerLayer);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.client.StandingHud::onClientTick);
        // Picking a villager's workstation out in the world.
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.client.JobPick::onClientTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.client.JobPick::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.client.JobPick::onRenderGui);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.client.JobPick::onUse);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.client.JobPick::onScreenOpening);
    }
}

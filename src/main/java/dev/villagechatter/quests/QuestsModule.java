package dev.villagechatter.quests;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.villagechatter.quests.quest.Discounts;
import dev.villagechatter.quests.quest.GuardianManager;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The Quests module (formerly the separate Village Quests mod): heirlooms, errands, job seekers.
 * Switch it off with {@code enabled = false} in villagechatter-quests.toml to keep only the dialogue.
 *
 * Everything it registers (quest note item, data components, attachments) and its datapack folders keep the
 * old {@code villagequests} namespace, so worlds and datapacks from Village Quests 0.1–0.3 carry over.
 */
public final class QuestsModule {
    private QuestsModule() {}

    public static final String NAMESPACE = "villagequests";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        QuestRegistries.register(modEventBus);
        modEventBus.addListener(dev.villagechatter.quests.journal.QuestNetwork::register);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) dev.villagechatter.quests.client.QuestsClient.init(modEventBus);

        // Quest talk normally happens inside the dialogue screen (see QuestDialogue). This LOWEST listener only
        // runs when the dialogue didn't take the click (villager dialogue switched off), and falls back to chat prompts.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, QuestManager::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(QuestManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(QuestManager::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.quests.quest.GuardQuests::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(QuestManager::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.Villages::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(GuardianManager::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(Discounts::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(GuardianManager::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.quests.quest.LostPet::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, dev.villagechatter.quests.quest.Security::onPlace);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, dev.villagechatter.quests.quest.Security::onBreak);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, dev.villagechatter.quests.quest.LostPet::onInteract);
        NeoForge.EVENT_BUS.addListener(GuardianManager::onServerStopped);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new QuestDefinitions()));
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new dev.villagechatter.reputation.Gifts.Loader()));
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.VillageLife::onBabySpawn);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> QuestCommands.register(event.getDispatcher()));

        modContainer.registerConfig(ModConfig.Type.COMMON, QuestConfig.SPEC, "villagechatter-quests.toml");
    }

    public static boolean enabled() {
        return QuestConfig.ENABLED.get();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}

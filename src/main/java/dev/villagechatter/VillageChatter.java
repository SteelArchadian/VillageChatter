package dev.villagechatter;

import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.server.ConversationManager;
import dev.villagechatter.server.PetTalk;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(VillageChatter.MODID)
public class VillageChatter {
    public static final String MODID = "villagechatter";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Entities that open a dialogue on right-click. Modpacks/datapacks can add to this tag. */
    public static final TagKey<EntityType<?>> TALKABLE =
            TagKey.create(Registries.ENTITY_TYPE, id("talkable"));

    public VillageChatter(IEventBus modEventBus, ModContainer modContainer) {
        ChatterSounds.register(modEventBus);
        ChatterAttachments.register(modEventBus);
        dev.villagechatter.reputation.faction.BannerLoyalty.register(modEventBus);
        modEventBus.addListener(ChatterNetwork::register);
        modEventBus.addListener(dev.villagechatter.reputation.StandingNetwork::register);
        modEventBus.addListener(dev.villagechatter.life.job.JobNetwork::register);

        // LOW priority so mods with their own NPC dialogue (e.g. Bountiful NPC) get first say and can cancel.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ConversationManager::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ConversationManager::onPetInteract);
        NeoForge.EVENT_BUS.addListener(ConversationManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(DebugCommands::onRegister);
        NeoForge.EVENT_BUS.addListener(ConversationManager::onLogout);
        NeoForge.EVENT_BUS.addListener(ConversationManager::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(ConversationManager::onTrade);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Speech::onTradeEvent);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.PiglinNature::onTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.PiglinNature::onInteract);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Speech::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(ConversationManager::onTradeScreenClosed);
        // Living villages: life outside the dialogue box.
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.LivingVillage::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.AmbientVoices::onLevelSound);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.GuardGear::onOpen);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, dev.villagechatter.life.FriendlyFire::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.FriendlyFire::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Law::onDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Law::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Law::onInteract);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Enforcement::onTarget);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Enforcement::onEffectAdded);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Enforcement::onJoin);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Enforcement::onTame);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Law::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Law::onGolemBuilt);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onToss);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onPickup);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onImpact);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.GoodDeeds::onConvert);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.law.Companion::onJoin);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.server.PetTalk::onHurt);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.GuardGear::onClose);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, dev.villagechatter.life.GuardTrust::onInteract);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Onlookers::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Misdeeds::onPlace);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Misdeeds::onBreak);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Misdeeds::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Misdeeds::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Misdeeds::onContainerClose);
        NeoForge.EVENT_BUS.addListener(PetTalk::onJoinLevel);
        // Kids keep their reputation when they grow up; newborns start from their parents'. Jobs players assign.
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.Upbringing::onJoin);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Grime::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Grime::onHurt);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.Grime::onKill);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.server.PiglinTalk::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Witnessed::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onBreak);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionDeeds::onContainerClose);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Sabotage::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Wipeout::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.PiglinQuests::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.BannerLoyalty::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.RaidOutcome::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.WorldEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.TownCrowd::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.TownCrowd::onDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.WorldEvents::onDamage);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.WorldEvents::onToss);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.RaidOutcome::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.BannerLoyalty::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Sabotage::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Sabotage::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.Sabotage::onContainerClose);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) ->
                dev.villagechatter.reputation.faction.Sabotage.onLogout(e.getEntity()));
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onEffectAdded);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.reputation.faction.FactionBook::onConvert);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.JobAssign::onVillagerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.JobAssign::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.villagechatter.life.job.JobAssign::onLogout);

        QuestsModule.init(modEventBus, modContainer);

        modContainer.registerConfig(ModConfig.Type.COMMON, ChatterConfig.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ChatterConfig.CLIENT_SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

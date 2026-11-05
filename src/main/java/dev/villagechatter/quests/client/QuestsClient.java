package dev.villagechatter.quests.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import dev.villagechatter.quests.journal.QuestNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the Quests module: the quest journal key (J by default). It's an ordinary key binding, so it shows
 * up in Controls, Controlling's searchable list and Controlify, and can be rebound there.
 */
public final class QuestsClient {
    private QuestsClient() {}

    public static final KeyMapping OPEN_JOURNAL = new KeyMapping("key.villagechatter.quest_journal",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.villagechatter");

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_JOURNAL));
        NeoForge.EVENT_BUS.addListener(QuestsClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_JOURNAL.consumeClick()) {
            if (mc.player != null && mc.screen == null && mc.getConnection() != null) {
                PacketDistributor.sendToServer(new QuestNetwork.OpenJournal());
            }
        }
    }
}

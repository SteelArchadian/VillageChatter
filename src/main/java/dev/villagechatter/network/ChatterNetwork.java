package dev.villagechatter.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.villagechatter.VillageChatter;
import dev.villagechatter.client.ClientDialogue;
import dev.villagechatter.server.ConversationManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ChatterNetwork {
    private ChatterNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("3");
        // ClientDialogue is only touched inside the handler body, so it is never loaded on a dedicated server.
        registrar.playToClient(OpenDialogue.TYPE, OpenDialogue.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) ClientDialogue.open(payload);
        });
        registrar.playToClient(DialogueLine.TYPE, DialogueLine.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) ClientDialogue.line(payload);
        });
        registrar.playToClient(CloseDialogue.TYPE, CloseDialogue.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) ClientDialogue.close(payload);
        });
        registrar.playToServer(DialogueAction.TYPE, DialogueAction.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) ConversationManager.onAction(player, payload);
        });
    }

    /** Flags describing the speaker's mood/context, so the client can pick fitting lines. */
    public static final int FLAG_VILLAGER = 1, FLAG_NIGHT = 2, FLAG_RAIN = 4, FLAG_HERO = 8, FLAG_GRUMPY = 16;
    /** Situations that override normal chit-chat: monsters close by, actively fighting, just won a fight. */
    public static final int FLAG_DANGER = 32, FLAG_COMBAT = 64, FLAG_VICTORY = 128;
    /** A child (higher voice, kid lines). */
    public static final int FLAG_BABY = 256;
    /** Golems: you're carrying a poppy / iron they'd take. */
    public static final int FLAG_POPPY = 512, FLAG_IRON = 1024;
    /** Pets: waiting for you at home / on their way home. */
    public static final int FLAG_HOME = 2048, FLAG_LEAVING = 4096;
    /** Living villages: you walked in on a chat / a guard is keeping an eye on you / a close friend has a surprise. */
    public static final int FLAG_INTERRUPTED = 8192, FLAG_WATCHED = 16384, FLAG_FRIEND_GIFT = 32768;
    /** Back from the trade screen: bought something / only browsed. */
    public static final int FLAG_AFTER_TRADE = 65536, FLAG_BROWSED = 131072;
    /** Pets (Doggy Talents): knocked out and recovering / getting hungry. */
    public static final int FLAG_HURT = 262144, FLAG_HUNGRY = 524288;

    /** Who's talking; decides the voice and text style. */
    public static final String KIND_VILLAGER = "villager", KIND_NPC = "npc", KIND_GOLEM = "golem", KIND_PET = "pet";
    /** Piglins: their own voice (tools/sound_gen/piglin_phrases.py) instead of the per-letter babble. */
    public static final String KIND_PIGLIN = "piglin";
    /** Piglins: you carry or wear gold; they like you; they don't. */
    public static final int FLAG_GOLD = 1 << 20, FLAG_LIKED = 1 << 21, FLAG_DISLIKED = 1 << 22;
    /** A guard in a fight, talking to someone on the raiders' side. */
    public static final int FLAG_BETRAYER = 1 << 23;

    private static final StreamCodec<ByteBuf, List<String>> STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(16));

    /**
     * Server-written text, for lines that can't come from lang pools (quests: names, amounts, coordinates).
     * {@code text} replaces the picked line; {@code labels} gives option ids a label instead of
     * villagechatter.option.&lt;id&gt;.
     */
    public record Extras(Map<String, Component> labels, Optional<Component> text) {
        public static final Extras NONE = new Extras(Map.of(), Optional.empty());
        public static final StreamCodec<RegistryFriendlyByteBuf, Extras> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ComponentSerialization.STREAM_CODEC, 16), Extras::labels,
                ComponentSerialization.OPTIONAL_STREAM_CODEC, Extras::text,
                Extras::new);

        public Component label(String id) {
            Component label = labels.get(id);
            return label != null ? label : Component.translatable("villagechatter.option." + id);
        }
    }

    /**
     * Server -> client: start a conversation.
     * speakerPool is e.g. "profession.minecraft.farmer" or "entity.minecraft.wolf"; the client uses it to find
     * lang keys like villagechatter.greeting.profession.minecraft.farmer.0. Options are ids of the choices the
     * server allows (lang key villagechatter.option.&lt;id&gt;).
     */
    public record OpenDialogue(int entityId, String speakerPool, int flags, String kind, List<String> options, Extras extras)
            implements CustomPacketPayload {
        public static final Type<OpenDialogue> TYPE = new Type<>(VillageChatter.id("open_dialogue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenDialogue> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenDialogue::entityId,
                ByteBufCodecs.STRING_UTF8, OpenDialogue::speakerPool,
                ByteBufCodecs.VAR_INT, OpenDialogue::flags,
                ByteBufCodecs.STRING_UTF8, OpenDialogue::kind,
                STRINGS, OpenDialogue::options,
                Extras.CODEC, OpenDialogue::extras,
                OpenDialogue::new);

        public OpenDialogue(int entityId, String speakerPool, int flags, String kind, List<String> options) {
            this(entityId, speakerPool, flags, kind, options, Extras.NONE);
        }

        @Override
        public Type<OpenDialogue> type() { return TYPE; }
    }

    /**
     * Server -> client: the speaker says something on a topic (e.g. "smalltalk", "reply.trick").
     * A non-empty option list replaces the current choices (submenus, things that changed).
     */
    public record DialogueLine(int entityId, String topic, List<String> options, Extras extras) implements CustomPacketPayload {
        public static final Type<DialogueLine> TYPE = new Type<>(VillageChatter.id("dialogue_line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DialogueLine::entityId,
                ByteBufCodecs.STRING_UTF8, DialogueLine::topic,
                STRINGS, DialogueLine::options,
                Extras.CODEC, DialogueLine::extras,
                DialogueLine::new);

        public DialogueLine(int entityId, String topic, List<String> options) {
            this(entityId, topic, options, Extras.NONE);
        }

        @Override
        public Type<DialogueLine> type() { return TYPE; }
    }

    /** Server -> client: conversation ended server-side (walked away, villager died...). */
    public record CloseDialogue(int entityId) implements CustomPacketPayload {
        public static final Type<CloseDialogue> TYPE = new Type<>(VillageChatter.id("close_dialogue"));
        public static final StreamCodec<ByteBuf, CloseDialogue> CODEC =
                ByteBufCodecs.VAR_INT.map(CloseDialogue::new, CloseDialogue::entityId);

        @Override
        public Type<CloseDialogue> type() { return TYPE; }
    }

    /** Client -> server: the player picked an option (its id), or "goodbye" when the window closed. */
    public record DialogueAction(int entityId, String action) implements CustomPacketPayload {
        public static final String TRADE = "trade", INTERACT = "interact", GOODBYE = "goodbye";

        public static final Type<DialogueAction> TYPE = new Type<>(VillageChatter.id("dialogue_action"));
        public static final StreamCodec<ByteBuf, DialogueAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DialogueAction::entityId,
                ByteBufCodecs.STRING_UTF8, DialogueAction::action,
                DialogueAction::new);

        @Override
        public Type<DialogueAction> type() { return TYPE; }
    }
}

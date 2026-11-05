package dev.villagechatter.quests.journal;

import java.util.List;
import java.util.UUID;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.UUIDUtil;
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

/** Quest journal packets: open (C2S), the list (S2C), give up / clear (C2S). */
public final class QuestNetwork {
    private QuestNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(OpenJournal.TYPE, OpenJournal.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) QuestJournal.send(player);
        });
        registrar.playToServer(QuestAction.TYPE, QuestAction.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) QuestJournal.onAction(player, payload);
        });
        // The client class is only touched inside the handler body, so a dedicated server never loads it.
        registrar.playToClient(JournalData.TYPE, JournalData.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) dev.villagechatter.quests.client.JournalScreen.show(payload.entries(), payload.standing());
        });
    }

    /** One row in the journal. Text is built on the server, so it can name villagers, items and places. */
    public record Entry(UUID instance, Component title, Component giver, Component location, Component status, boolean failed) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::instance,
                ComponentSerialization.STREAM_CODEC, Entry::title,
                ComponentSerialization.STREAM_CODEC, Entry::giver,
                ComponentSerialization.STREAM_CODEC, Entry::location,
                ComponentSerialization.STREAM_CODEC, Entry::status,
                ByteBufCodecs.BOOL, Entry::failed,
                Entry::new);
    }

    public record OpenJournal() implements CustomPacketPayload {
        public static final Type<OpenJournal> TYPE = new Type<>(QuestsModule.id("open_journal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenJournal> CODEC = StreamCodec.unit(new OpenJournal());

        @Override
        public Type<OpenJournal> type() { return TYPE; }
    }

    /** The quest rows, and the player's faction standings line (empty when factions are off). */
    public record JournalData(List<Entry> entries, Component standing) implements CustomPacketPayload {
        public static final Type<JournalData> TYPE = new Type<>(QuestsModule.id("journal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalData> CODEC = StreamCodec.composite(
                Entry.CODEC.apply(ByteBufCodecs.list(64)), JournalData::entries,
                ComponentSerialization.STREAM_CODEC, JournalData::standing,
                JournalData::new);

        @Override
        public Type<JournalData> type() { return TYPE; }
    }

    /** "quit" (give up an active quest) or "dismiss" (clear a failed one from the list). */
    public record QuestAction(UUID instance, String action) implements CustomPacketPayload {
        public static final String QUIT = "quit", DISMISS = "dismiss";
        public static final Type<QuestAction> TYPE = new Type<>(QuestsModule.id("quest_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuestAction> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, QuestAction::instance,
                ByteBufCodecs.STRING_UTF8, QuestAction::action,
                QuestAction::new);

        @Override
        public Type<QuestAction> type() { return TYPE; }
    }
}

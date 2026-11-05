package dev.villagechatter.reputation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.VillageChatter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** "What's my standing with what I'm looking at?" (C2S) and the answer (S2C). */
public final class StandingNetwork {
    private StandingNetwork() {}

    public static final int VILLAGER = 0, BELL = 1, PILLAGER = 2, PIGLIN = 3, BANNER = 4;
    /** Ignore requests faster than this (ticks), per player. */
    private static final int MIN_INTERVAL = 5;
    private static final Map<UUID, Integer> LAST_REQUEST = new HashMap<>();

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(LookAt.TYPE, LookAt.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) answer(player, payload);
        });
        registrar.playToServer(GiveGift.TYPE, GiveGift.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) gift(player, payload);
        });
        registrar.playToClient(StandingInfo.TYPE, StandingInfo.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) dev.villagechatter.reputation.client.StandingHud.receive(payload);
        });
    }

    private static void answer(ServerPlayer player, LookAt look) {
        int now = player.server.getTickCount();
        Integer last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL) return;
        LAST_REQUEST.put(player.getUUID(), now);
        if (LAST_REQUEST.size() > 256) LAST_REQUEST.clear();

        Standing.View view = null;
        if (look.kind() == VILLAGER) {
            if (player.serverLevel().getEntity(look.entityId()) instanceof Villager villager
                    && villager.distanceToSqr(player) < 32 * 32) {
                view = Standing.forVillager(player, villager);
            }
        } else if (look.kind() == BELL && look.pos().distSqr(player.blockPosition()) < 32 * 32
                && player.serverLevel().isLoaded(look.pos())) {
            view = Standing.forBell(player, player.serverLevel(), look.pos());
        } else if (look.kind() == PILLAGER || look.kind() == PIGLIN) {
            var e = player.serverLevel().getEntity(look.entityId());
            if (e != null && e.distanceToSqr(player) < 32 * 32) {
                if (look.kind() == PILLAGER && e instanceof net.minecraft.world.entity.monster.AbstractIllager) view = Standing.forPillagers(player);
                if (look.kind() == PIGLIN && e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) view = Standing.forPiglins(player);
            }
        } else if (look.kind() == BANNER && look.pos().distSqr(player.blockPosition()) < 32 * 32
                && player.serverLevel().isLoaded(look.pos()) && Standing.isOminousBanner(player.serverLevel(), look.pos())) {
            view = Standing.forPillagers(player);
        }
        if (view == null || !player.connection.hasChannel(StandingInfo.TYPE)) return;
        PacketDistributor.sendToPlayer(player, new StandingInfo(look.kind(), look.entityId(), look.pos(),
                view.title(), view.color(), view.detail()));
    }

    /** The villager reacts in the dialogue, then the usual options come back. */
    private static void gift(ServerPlayer player, GiveGift gift) {
        if (player.serverLevel().getEntity(gift.entityId()) instanceof net.minecraft.world.entity.Mob guard
                && dev.villagechatter.life.GuardTrust.isGuard(guard) && guard.distanceToSqr(player) <= 8 * 8) {
            // A guard you've earned the trust of takes gear (life.GuardGear).
            if (!dev.villagechatter.life.GuardTrust.trusts(player, guard)) return;
            Component line = dev.villagechatter.life.GuardGear.give(player, guard, gift.slot());
            PacketDistributor.sendToPlayer(player, new dev.villagechatter.network.ChatterNetwork.DialogueLine(guard.getId(), "gift",
                    java.util.List.of(dev.villagechatter.server.ConversationManager.GIFT, "smalltalk",
                            dev.villagechatter.network.ChatterNetwork.DialogueAction.GOODBYE),
                    new dev.villagechatter.network.ChatterNetwork.Extras(java.util.Map.of(), java.util.Optional.of(line))));
            return;
        }
        if (player.serverLevel().getEntity(gift.entityId()) instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin
                && piglin.distanceToSqr(player) <= 8 * 8) {
            Component line = dev.villagechatter.server.PiglinGifts.give(player, piglin, gift.slot());
            PacketDistributor.sendToPlayer(player, new dev.villagechatter.network.ChatterNetwork.DialogueLine(piglin.getId(), "piglin_gift",
                    dev.villagechatter.server.PiglinTalk.options(player),
                    new dev.villagechatter.network.ChatterNetwork.Extras(java.util.Map.of(), java.util.Optional.of(line))));
            return;
        }
        if (player.serverLevel().getEntity(gift.entityId()) instanceof net.minecraft.world.entity.monster.AbstractIllager illager
                && illager.distanceToSqr(player) <= 8 * 8 && dev.villagechatter.reputation.faction.PillagerTalk.canTalk(player, illager)) {
            Component line = dev.villagechatter.reputation.faction.PillagerGifts.give(player, illager, gift.slot());
            java.util.List<String> next = new java.util.ArrayList<>(dev.villagechatter.reputation.faction.PillagerTalk.options(player, illager));
            next.add(dev.villagechatter.network.ChatterNetwork.DialogueAction.GOODBYE);
            PacketDistributor.sendToPlayer(player, new dev.villagechatter.network.ChatterNetwork.DialogueLine(illager.getId(), "gift", next,
                    new dev.villagechatter.network.ChatterNetwork.Extras(java.util.Map.of(), java.util.Optional.of(line))));
            return;
        }
        if (!(player.serverLevel().getEntity(gift.entityId()) instanceof Villager villager) || villager.distanceToSqr(player) > 8 * 8) return;
        Gifts.Result result = Gifts.give(player, villager, gift.slot());
        java.util.List<String> options = new java.util.ArrayList<>();
        java.util.Map<String, Component> labels = java.util.Map.of();
        if (dev.villagechatter.quests.QuestsModule.enabled()) {
            var quest = dev.villagechatter.quests.quest.QuestDialogue.open(player, villager);
            options.addAll(quest.options());
            labels = quest.labels();
        }
        options.addAll(dev.villagechatter.server.ConversationManager.villagerOptions(villager));
        options.add(dev.villagechatter.network.ChatterNetwork.DialogueAction.GOODBYE);
        PacketDistributor.sendToPlayer(player, new dev.villagechatter.network.ChatterNetwork.DialogueLine(villager.getId(),
                "gift_" + result.reaction().name().toLowerCase(java.util.Locale.ROOT), options,
                new dev.villagechatter.network.ChatterNetwork.Extras(labels, java.util.Optional.of(result.line()))));
    }

    public record GiveGift(int entityId, int slot) implements CustomPacketPayload {
        public static final Type<GiveGift> TYPE = new Type<>(VillageChatter.id("give_gift"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GiveGift> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GiveGift::entityId,
                ByteBufCodecs.VAR_INT, GiveGift::slot,
                GiveGift::new);

        @Override
        public Type<GiveGift> type() { return TYPE; }
    }

    public record LookAt(int kind, int entityId, BlockPos pos) implements CustomPacketPayload {
        public static final Type<LookAt> TYPE = new Type<>(VillageChatter.id("look_at"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LookAt> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LookAt::kind,
                ByteBufCodecs.VAR_INT, LookAt::entityId,
                BlockPos.STREAM_CODEC, LookAt::pos,
                LookAt::new);

        @Override
        public Type<LookAt> type() { return TYPE; }
    }

    public record StandingInfo(int kind, int entityId, BlockPos pos, Component title, int color, Component detail)
            implements CustomPacketPayload {
        public static final Type<StandingInfo> TYPE = new Type<>(VillageChatter.id("standing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StandingInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StandingInfo::kind,
                ByteBufCodecs.VAR_INT, StandingInfo::entityId,
                BlockPos.STREAM_CODEC, StandingInfo::pos,
                ComponentSerialization.STREAM_CODEC, StandingInfo::title,
                ByteBufCodecs.INT, StandingInfo::color,
                ComponentSerialization.STREAM_CODEC, StandingInfo::detail,
                StandingInfo::new);

        @Override
        public Type<StandingInfo> type() { return TYPE; }
    }
}

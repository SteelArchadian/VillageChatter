package dev.villagechatter.life.job;

import java.util.List;

import dev.villagechatter.VillageChatter;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
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

/** "About your job": what the villager can do and which workstations are nearby (S2C), and the player's choice (C2S). */
public final class JobNetwork {
    private JobNetwork() {}

    /** JobInfo flags. */
    public static final int FLAG_KID = 1, FLAG_NITWIT = 2, FLAG_TRADED = 4, FLAG_TRADED_LOCKED = 8;
    /** JobRequest actions. */
    public static final String PICK = "pick", ASSIGN = "assign", CANCEL = "cancel";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(JobInfo.TYPE, JobInfo.CODEC, (payload, ctx) -> {
            if (FMLEnvironment.dist.isClient()) dev.villagechatter.life.job.client.ClientJobs.receive(payload);
        });
        registrar.playToServer(JobRequest.TYPE, JobRequest.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) JobAssign.onRequest(player, payload);
        });
    }

    /** A workstation near the villager: whose job block it is, and who works there now (empty name = free). */
    public record Site(BlockPos pos, String profession, Component owner, String ownerJob, boolean theirs) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Site> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Site::pos,
                ByteBufCodecs.STRING_UTF8, Site::profession,
                ComponentSerialization.STREAM_CODEC, Site::owner,
                ByteBufCodecs.STRING_UTF8, Site::ownerJob,
                ByteBufCodecs.BOOL, Site::theirs,
                Site::new);

        public boolean free() {
            return owner.getString().isEmpty();
        }
    }

    /** Server -> client: open the job screen for this villager. */
    public record JobInfo(int entityId, String current, int flags, int radius, int seconds, List<Site> sites)
            implements CustomPacketPayload {
        public static final Type<JobInfo> TYPE = new Type<>(VillageChatter.id("job_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JobInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, JobInfo::entityId,
                ByteBufCodecs.STRING_UTF8, JobInfo::current,
                ByteBufCodecs.VAR_INT, JobInfo::flags,
                ByteBufCodecs.VAR_INT, JobInfo::radius,
                ByteBufCodecs.VAR_INT, JobInfo::seconds,
                Site.CODEC.apply(ByteBufCodecs.list(512)), JobInfo::sites,
                JobInfo::new);

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }

        @Override
        public Type<JobInfo> type() { return TYPE; }
    }

    /** Client -> server: start picking in the world, assign a workstation, or stop picking. */
    public record JobRequest(int entityId, String action, String profession, BlockPos pos) implements CustomPacketPayload {
        public static final Type<JobRequest> TYPE = new Type<>(VillageChatter.id("job_request"));
        public static final StreamCodec<ByteBuf, JobRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, JobRequest::entityId,
                ByteBufCodecs.STRING_UTF8, JobRequest::action,
                ByteBufCodecs.STRING_UTF8, JobRequest::profession,
                BlockPos.STREAM_CODEC, JobRequest::pos,
                JobRequest::new);

        @Override
        public Type<JobRequest> type() { return TYPE; }
    }
}

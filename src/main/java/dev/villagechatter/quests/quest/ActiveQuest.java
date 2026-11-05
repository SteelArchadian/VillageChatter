package dev.villagechatter.quests.quest;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

/** One quest a player has taken. Mutable; lives in the player's {@link PlayerQuests} attachment. */
public final class ActiveQuest {
    public enum State implements StringRepresentable {
        /** Running. */
        ACTIVE,
        /** Deadline passed; {@link #pendingRep} is applied the next time the player meets the giver. */
        EXPIRED,
        /** Over without success (giver died, ran out of time, gave up). Kept for the journal; {@link #failReason} says why. */
        FAILED;

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final Codec<ActiveQuest> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("instance").forGetter(q -> q.instance),
            ResourceLocation.CODEC.fieldOf("quest").forGetter(q -> q.questId),
            UUIDUtil.CODEC.fieldOf("giver").forGetter(q -> q.giver),
            ComponentSerialization.CODEC.fieldOf("giver_name").forGetter(q -> q.giverName),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(q -> q.dimension),
            BlockPos.CODEC.fieldOf("giver_pos").forGetter(q -> q.giverPos),
            BlockPos.CODEC.fieldOf("target").forGetter(q -> q.target),
            Codec.STRING.fieldOf("kind").forGetter(q -> q.kind),
            Codec.LONG.fieldOf("accepted_at").forGetter(q -> q.acceptedAt),
            Codec.LONG.fieldOf("deadline").forGetter(q -> q.deadline),
            State.CODEC.fieldOf("state").forGetter(q -> q.state),
            Codec.BOOL.optionalFieldOf("item_dropped", false).forGetter(q -> q.itemDropped),
            Codec.INT.optionalFieldOf("pending_rep", 0).forGetter(q -> q.pendingRep),
            Codec.INT.optionalFieldOf("count", 0).forGetter(q -> q.count),
            UUIDUtil.CODEC.optionalFieldOf("buddy").forGetter(q -> java.util.Optional.ofNullable(q.buddy)),
            Tail.CODEC.forGetter(q -> new Tail(q.failReason, q.progress))
    ).apply(i, (a, b, c, d, e, f, g, h, i2, j, k, l, m, n, o, p) -> {
        ActiveQuest q = new ActiveQuest(a, b, c, d, e, f, g, h, i2, j, k, l, m, n);
        q.buddy = o.orElse(null);
        q.failReason = p.failReason();
        q.progress = p.progress();
        return q;
    }));

    /** Fields past RecordCodecBuilder's 16, stored flat alongside the others (same keys as before). */
    private record Tail(String failReason, int progress) {
        static final com.mojang.serialization.MapCodec<Tail> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("fail_reason", "").forGetter(Tail::failReason),
                Codec.INT.optionalFieldOf("progress", 0).forGetter(Tail::progress)
        ).apply(i, Tail::new));
    }

    public final UUID instance;
    public final ResourceLocation questId;
    public final UUID giver;
    public final Component giverName;
    public final ResourceKey<Level> dimension;
    /** The giver's village (anchor bell of their town, or where they stood). Updated if they move to another village. */
    public BlockPos giverPos;
    public final BlockPos target;
    /** "cave", "surface", a structure id like "minecraft:mineshaft", or "errand" (target = where the giver lives). */
    public final String kind;
    public final long acceptedAt;
    public long deadline;
    public State state;
    /** Heirlooms: the guardian has died and dropped the heirloom. Rescues: the captive has been freed. */
    public boolean itemDropped;
    /** Reputation change still owed to the giver (applied when they're next nearby). */
    public int pendingRep;
    /** Errands: how many items were asked for. */
    public final int count;
    /** Rescues: the captive villager, once spawned. ({@link #itemDropped} = rescued.) */
    @javax.annotation.Nullable
    public UUID buddy;
    /**
     * Why a FAILED quest failed: a lang key (args: giver name, then the optional extra), optionally "key|extra",
     * e.g. "villagequests.fail.recipient_died|Kevin".
     */
    public String failReason = "";
    /** Counted progress for quests the world can't be asked about (security: defensive blocks placed so far). */
    public int progress;

    /** The fail reason as text for the journal. */
    public net.minecraft.network.chat.Component failReasonText() {
        int bar = failReason.indexOf('|');
        return bar < 0
                ? net.minecraft.network.chat.Component.translatable(failReason, giverName)
                : net.minecraft.network.chat.Component.translatable(failReason.substring(0, bar), giverName, failReason.substring(bar + 1));
    }

    /** Failed through nobody's fault (someone else died): the giver owes you something for your time. */
    public boolean compensationOwed() {
        return state == State.FAILED && pendingRep > 0;
    }

    public ActiveQuest(UUID instance, ResourceLocation questId, UUID giver, Component giverName,
                       ResourceKey<Level> dimension, BlockPos giverPos, BlockPos target, String kind,
                       long acceptedAt, long deadline, State state, boolean itemDropped, int pendingRep, int count) {
        this.instance = instance;
        this.questId = questId;
        this.giver = giver;
        this.giverName = giverName;
        this.dimension = dimension;
        this.giverPos = giverPos;
        this.target = target;
        this.kind = kind;
        this.acceptedAt = acceptedAt;
        this.deadline = deadline;
        this.state = state;
        this.itemDropped = itemDropped;
        this.pendingRep = pendingRep;
        this.count = count;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }
}

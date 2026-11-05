package dev.villagechatter.quests.quest;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Everything a quest note shows. {@code kind} is "cave", "surface" or a structure id. */
public record NoteData(UUID instance, Component title, Component giver, String kind, BlockPos target, long deadline) {
    public static final Codec<NoteData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("instance").forGetter(NoteData::instance),
            ComponentSerialization.CODEC.fieldOf("title").forGetter(NoteData::title),
            ComponentSerialization.CODEC.fieldOf("giver").forGetter(NoteData::giver),
            Codec.STRING.fieldOf("kind").forGetter(NoteData::kind),
            BlockPos.CODEC.fieldOf("target").forGetter(NoteData::target),
            Codec.LONG.fieldOf("deadline").forGetter(NoteData::deadline)
    ).apply(i, NoteData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoteData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, NoteData::instance,
            ComponentSerialization.STREAM_CODEC, NoteData::title,
            ComponentSerialization.STREAM_CODEC, NoteData::giver,
            ByteBufCodecs.STRING_UTF8, NoteData::kind,
            BlockPos.STREAM_CODEC, NoteData::target,
            ByteBufCodecs.VAR_LONG, NoteData::deadline,
            NoteData::new);
}

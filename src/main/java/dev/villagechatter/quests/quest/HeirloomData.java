package dev.villagechatter.quests.quest;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/** On a recovered heirloom: the quest it belongs to and the player who recovered it. */
public record HeirloomData(UUID instance, UUID owner) {
    public static final Codec<HeirloomData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("instance").forGetter(HeirloomData::instance),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(HeirloomData::owner)
    ).apply(i, HeirloomData::new));

    public static final StreamCodec<ByteBuf, HeirloomData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, HeirloomData::instance,
            UUIDUtil.STREAM_CODEC, HeirloomData::owner,
            HeirloomData::new);
}

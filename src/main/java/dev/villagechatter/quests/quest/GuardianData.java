package dev.villagechatter.quests.quest;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/** Stored on a guardian mob: which quest instance it guards, and whose quest that is. */
public record GuardianData(UUID instance, UUID owner) {
    private static final UUID NIL = new UUID(0L, 0L);
    public static final GuardianData NONE = new GuardianData(NIL, NIL);

    public static final Codec<GuardianData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("instance").forGetter(GuardianData::instance),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(GuardianData::owner)
    ).apply(i, GuardianData::new));

    public boolean isNone() {
        return instance.equals(NIL);
    }
}

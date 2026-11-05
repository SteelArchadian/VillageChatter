package dev.villagechatter.quests.quest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;

/**
 * A player's fractional reputation per villager, waiting to be paid out as gossip.
 * Vanilla deletes gossip entries below 2, so small rewards collect here first.
 */
public final class Goodwill {
    public static final Codec<Goodwill> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.DOUBLE).xmap(Goodwill::new, g -> g.points);

    public final Map<UUID, Double> points;

    public Goodwill() {
        this.points = new HashMap<>();
    }

    private Goodwill(Map<UUID, Double> points) {
        this.points = new HashMap<>(points);
    }

    public double get(UUID villager) {
        return points.getOrDefault(villager, 0.0);
    }
}

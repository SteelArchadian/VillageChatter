package dev.villagechatter.server;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

/**
 * "Seen any bees around?" Naturalist professions know what's living near their village:
 * farmers, florists and beekeepers know the wild bee nests; shepherds know the flocks; butchers and hunters know
 * where the game is. Cheap lookups only: the point-of-interest index for nests, loaded entities for animals.
 */
public final class Sightings {
    private Sightings() {}

    public enum Kind {
        BEES("sight_bees"), SHEEP("sight_sheep"), GAME("sight_game");

        public final String option;

        Kind(String option) {
            this.option = option;
        }
    }

    /** Which sighting (if any) this villager can tell you about, by profession (any mod's). */
    @Nullable
    public static Kind kindFor(Villager villager) {
        if (villager.isBaby()) return null;
        ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        String path = prof.getPath();
        if (path.contains("bee") || path.equals("farmer") || path.equals("florist")) return Kind.BEES;
        if (path.equals("shepherd")) return Kind.SHEEP;
        if (path.equals("butcher") || path.equals("hunter")) return Kind.GAME;
        return null;
    }

    public static Kind byOption(String option) {
        for (Kind k : Kind.values()) if (k.option.equals(option)) return k;
        return null;
    }

    /** The villager's answer: where it is (direction + rough distance), or that they haven't seen any lately. */
    public static Component answer(Villager villager, Kind kind) {
        ServerLevel level = (ServerLevel) villager.level();
        int radius = ChatterConfig.SIGHTING_RADIUS.get();
        RandomSource random = villager.getRandom();
        BlockPos from = villager.blockPosition();
        return switch (kind) {
            case BEES -> {
                Optional<BlockPos> nest = level.getPoiManager().findClosest(
                        poi -> poi.is(PoiTypes.BEE_NEST), from, radius, PoiManager.Occupancy.ANY);
                yield nest.map(pos -> found("bees", from, pos, random, null))
                        .orElseGet(() -> none("bees", random));
            }
            case SHEEP -> {
                Sheep sheep = nearest(level, Sheep.class, from, radius);
                yield sheep == null ? none("sheep", random)
                        : found("sheep", from, sheep.blockPosition(), random,
                                Component.translatable("color.minecraft." + sheep.getColor().getName()));
            }
            case GAME -> {
                Entity game = nearestOf(level, from, radius, List.of(Cow.class, Pig.class, Goat.class, Rabbit.class));
                yield game == null ? none("game", random)
                        : found("game", from, game.blockPosition(), random, game.getType().getDescription());
            }
        };
    }

    private static Component found(String what, BlockPos from, BlockPos at, RandomSource random, @Nullable Component detail) {
        int dx = at.getX() - from.getX(), dz = at.getZ() - from.getZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        int round = dist < 100 ? 10 : 25;
        int rough = (int) Math.max(round, Math.round(dist / round) * round);
        Component dir = Component.translatable("villagequests.dir." + QuestText.direction(dx, dz));
        String key = "villagechatter.sighting." + what + ".found." + random.nextInt(3);
        return detail == null ? Component.translatable(key, dir, rough) : Component.translatable(key, dir, rough, detail);
    }

    private static Component none(String what, RandomSource random) {
        return Component.translatable("villagechatter.sighting." + what + ".none." + random.nextInt(2));
    }

    @Nullable
    private static <T extends Entity> T nearest(ServerLevel level, Class<T> type, BlockPos from, int radius) {
        return level.getEntitiesOfClass(type, new AABB(from).inflate(radius), Entity::isAlive).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(from.getX(), from.getY(), from.getZ()))).orElse(null);
    }

    @Nullable
    private static Entity nearestOf(ServerLevel level, BlockPos from, int radius, List<Class<? extends Entity>> types) {
        Entity best = null;
        for (Class<? extends Entity> type : types) {
            Entity e = nearest(level, type, from, radius);
            if (e != null && (best == null || e.distanceToSqr(from.getCenter()) < best.distanceToSqr(from.getCenter()))) best = e;
        }
        return best;
    }
}

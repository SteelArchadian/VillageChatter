package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Biome rumors: "the miners talk about caves full of glowing vines to the west, about 900 blocks". Villagers know
 * the remarkable places around them, flavored by profession (miners know caves, florists know flower groves,
 * oceanographers know reefs). Found by asking the world generator what biome would be at sample points, which needs
 * no chunk loading and costs a few milliseconds at most.
 */
public final class BiomeRumors {
    private BiomeRumors() {}

    private static final int MIN_BLOCKS = 200, MAX_BLOCKS = 2400, STEP = 96;
    private static final int SURFACE_Y = 70, CAVE_Y = 0, DEEP_Y = -40;

    /** A remarkable biome, the height to look for it at, and whether it's a rare (trusted-only) one. */
    record Target(ResourceKey<Biome> biome, int y, boolean rare) {}

    private static Target t(String id, int y) {
        return new Target(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(id)), y, false);
    }

    private static Target rare(String id, int y) {
        return new Target(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(id)), y, true);
    }

    static final List<Target> ALL = List.of(
            t("lush_caves", CAVE_Y), t("dripstone_caves", CAVE_Y), rare("deep_dark", DEEP_Y),
            t("mushroom_fields", SURFACE_Y), t("cherry_grove", SURFACE_Y), t("badlands", SURFACE_Y),
            t("ice_spikes", SURFACE_Y), t("mangrove_swamp", SURFACE_Y), t("bamboo_jungle", SURFACE_Y),
            t("flower_forest", SURFACE_Y), t("sunflower_plains", SURFACE_Y), t("dark_forest", SURFACE_Y),
            t("old_growth_pine_taiga", SURFACE_Y), t("warm_ocean", SURFACE_Y - 20), t("meadow", SURFACE_Y));

    /** What each profession (by id path, any mod) pays attention to. Others know a bit of everything. */
    private static final Map<String, List<String>> BY_PROFESSION = Map.ofEntries(
            Map.entry("farmer", List.of("sunflower_plains", "flower_forest", "meadow", "cherry_grove")),
            Map.entry("florist", List.of("flower_forest", "cherry_grove", "sunflower_plains", "meadow")),
            Map.entry("forester", List.of("dark_forest", "old_growth_pine_taiga", "bamboo_jungle", "cherry_grove")),
            Map.entry("miner", List.of("lush_caves", "dripstone_caves", "deep_dark", "badlands")),
            Map.entry("toolsmith", List.of("dripstone_caves", "badlands", "lush_caves")),
            Map.entry("armorer", List.of("dripstone_caves", "badlands")),
            Map.entry("oceanographer", List.of("warm_ocean", "mushroom_fields", "mangrove_swamp")),
            Map.entry("fisherman", List.of("warm_ocean", "mangrove_swamp", "mushroom_fields")),
            Map.entry("cleric", List.of("deep_dark", "mushroom_fields")),
            Map.entry("shepherd", List.of("meadow", "cherry_grove", "flower_forest")),
            Map.entry("hunter", List.of("bamboo_jungle", "old_growth_pine_taiga", "ice_spikes")),
            Map.entry("cartographer", List.of("ice_spikes", "badlands", "mushroom_fields", "cherry_grove", "lush_caves")));

    /**
     * A biome rumor this player hasn't heard, or null. {@code trusted} unlocks the rare ones (the deep dark).
     * Positions are remembered in {@code told}, rounded to 256-block cells so one region isn't told twice.
     */
    @Nullable
    public static Component tell(Villager villager, Rumors.Told told, boolean trusted) {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        String path = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).getPath();
        List<Target> wanted = new ArrayList<>();
        List<String> favorites = BY_PROFESSION.get(path);
        for (Target target : ALL) {
            if (target.rare() && !trusted) continue;
            if (favorites == null || favorites.contains(target.biome().location().getPath())) wanted.add(target);
        }
        if (wanted.isEmpty()) return null;

        var source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        BlockPos center = villager.blockPosition();
        int angleOffset = level.random.nextInt(360);
        // Rings outward, so the nearest remarkable place is found first.
        for (int r = MIN_BLOCKS; r <= MAX_BLOCKS; r += STEP) {
            int samples = Math.max(8, (int) (2 * Math.PI * r / STEP));
            for (int i = 0; i < samples; i++) {
                double a = Math.toRadians(angleOffset + 360.0 * i / samples);
                int x = center.getX() + (int) (Math.cos(a) * r), z = center.getZ() + (int) (Math.sin(a) * r);
                for (Target target : wanted) {
                    Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(target.y()),
                            QuartPos.fromBlock(z), sampler);
                    if (!biome.is(target.biome())) continue;
                    long cell = BlockPos.asLong(x >> 8, 0, z >> 8);
                    if (told.positions.contains(cell)) continue;
                    told.positions.add(cell);
                    return line(villager, x, z, target, level);
                }
            }
        }
        return null;
    }

    private static Component line(Villager villager, int x, int z, Target target, ServerLevel level) {
        int dx = x - villager.getBlockX(), dz = z - villager.getBlockZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        int round = dist < 500 ? 50 : 100;
        int rough = (int) Math.max(round, Math.round(dist / round) * round);
        Component name = Component.translatable("biome.minecraft." + target.biome().location().getPath());
        Component dir = Component.translatable("villagequests.dir." + QuestText.direction(dx, dz));
        String kind = target.y() < SURFACE_Y - 30 ? "cave" : "land";
        return Component.translatable("villagequests.rumor.biome." + kind + "." + level.random.nextInt(4), name, dir, rough);
    }
}

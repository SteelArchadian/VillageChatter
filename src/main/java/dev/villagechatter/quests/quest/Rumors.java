package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Rumors: villagers sometimes know about a real structure out in the world ("one of ours ran off east, about
 * 1200 blocks, saying there was a jungle temple out there; never came back"). Direction and rough distance only,
 * never coordinates. Each structure is told to a player once ({@link Told}). Villagers who dislike you keep quiet.
 *
 * Which structures can come up is the {@code #villagequests:rumor_sites} structure tag, so packs can add modded ones.
 * Room to grow (Village Chatter roadmap, phase 6): biome rumors, beekeepers who know hive locations,
 * better rumors for better reputation.
 */
public final class Rumors {
    private Rumors() {}

    public static final TagKey<Structure> RUMOR_SITES = TagKey.create(Registries.STRUCTURE, QuestsModule.id("rumor_sites"));
    /** How far villagers have "heard" of things. Uses {@link StructureScan}, so the range costs next to nothing. */
    private static final int MIN_BLOCKS = 150, MAX_BLOCKS = 4000;
    private static final int TRIES = 4;
    /** Below this, villagers don't share what they know. Same line as Village Chatter's grumpy greetings. */
    private static final int MIN_REPUTATION = -20;
    private static final int LINE_COUNT = 6;

    /** Structure positions (as longs) a player has already heard about. Saved with the player. */
    public static final class Told {
        public static final Codec<Told> CODEC = Codec.LONG.listOf().xmap(l -> new Told(new HashSet<>(l)), t -> new ArrayList<>(t.positions));
        public final Set<Long> positions;

        public Told() {
            this(new HashSet<>());
        }

        private Told(Set<Long> positions) {
            this.positions = positions;
        }
    }

    /** Better standing unlocks better rumors: common sites for everyone, then good ones, then rare ones. */
    public static final TagKey<Structure> RUMOR_SITES_GOOD = TagKey.create(Registries.STRUCTURE, QuestsModule.id("rumor_sites_good"));
    public static final TagKey<Structure> RUMOR_SITES_RARE = TagKey.create(Registries.STRUCTURE, QuestsModule.id("rumor_sites_rare"));
    private static final int FRIENDLY = 10, BELOVED = 50;
    /** Share of rumors that are about a remarkable biome rather than a structure. */
    private static final double BIOME_SHARE = 0.35;

    enum Tier { COMMON, GOOD, RARE }

    static Tier tier(ServerPlayer player, Villager villager) {
        int rep = villager.getPlayerReputation(player);
        if (rep >= BELOVED || player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) return Tier.RARE;
        return rep >= FRIENDLY ? Tier.GOOD : Tier.COMMON;
    }

    /**
     * A rumor this player hasn't heard yet (a structure or a remarkable biome), opened with a lead-in that fits how
     * much the villager trusts them; null if there's nothing to tell or they won't talk.
     */
    @Nullable
    public static Component tell(ServerPlayer player, Villager villager) {
        if (villager.getPlayerReputation(player) < MIN_REPUTATION || !(villager.level() instanceof ServerLevel level)) return null;
        Told told = player.getData(QuestRegistries.RUMORS_TOLD);
        Tier tier = tier(player, villager);
        Component body = null;
        if (level.random.nextDouble() < BIOME_SHARE) body = BiomeRumors.tell(villager, told, tier != Tier.COMMON);
        if (body == null) body = structureRumor(player, villager, level, told, tier);
        if (body == null) body = BiomeRumors.tell(villager, told, tier != Tier.COMMON);
        if (body == null) return null;
        String lead = "villagequests.rumor.lead." + tier.name().toLowerCase(java.util.Locale.ROOT) + "." + level.random.nextInt(4);
        return Component.translatable(lead).append(" ").append(body);
    }

    @Nullable
    private static Component structureRumor(ServerPlayer player, Villager villager, ServerLevel level, Told told, Tier tier) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        // Weighted pool: rarer sites count for more once they're unlocked, so trust is noticeably rewarded.
        List<Holder<Structure>> sites = new ArrayList<>();
        registry.getTag(RUMOR_SITES).ifPresent(tag -> tag.forEach(sites::add));
        if (tier != Tier.COMMON) registry.getTag(RUMOR_SITES_GOOD).ifPresent(tag -> tag.forEach(h -> { sites.add(h); sites.add(h); }));
        if (tier == Tier.RARE) registry.getTag(RUMOR_SITES_RARE).ifPresent(tag -> tag.forEach(h -> { sites.add(h); sites.add(h); sites.add(h); }));
        if (sites.isEmpty()) return null;

        for (int i = 0; i < TRIES; i++) {
            Holder<Structure> site = sites.get(level.random.nextInt(sites.size()));
            for (ChunkPos chunk : StructureScan.candidates(level, site, villager.blockPosition(), MIN_BLOCKS, MAX_BLOCKS)) {
                BlockPos pos = chunk.getMiddleBlockPosition(0);
                if (told.positions.contains(pos.asLong())) continue;
                told.positions.add(pos.asLong());
                return line(villager, pos, site, level, tier == Tier.RARE);
            }
        }
        return null;
    }

    /** Debug: the nearest structure of every rumor site from pos, with how long each lookup took. */
    public static List<String> survey(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        registry.getTag(RUMOR_SITES).ifPresent(tag -> tag.forEach(site -> {
            long t = System.nanoTime();
            List<ChunkPos> found = StructureScan.candidates(level, site, pos, MIN_BLOCKS, MAX_BLOCKS);
            double ms = (System.nanoTime() - t) / 1_000_000.0;
            String id = site.unwrapKey().map(k -> k.location().toString()).orElse("?");
            String nearest = found.isEmpty() ? "none in range" : found.get(0).getMiddleBlockPosition(0).toShortString()
                    + " (" + (int) Math.sqrt(found.get(0).getMiddleBlockPosition(pos.getY()).distSqr(pos)) + " blocks)";
            out.add(String.format(java.util.Locale.ROOT, "%s: %d candidates, nearest %s, %.2f ms", id, found.size(), nearest, ms));
        }));
        return out;
    }

    private static Component line(Villager villager, BlockPos pos, Holder<Structure> structure, ServerLevel level, boolean precise) {
        ResourceLocation id = structure.unwrapKey().map(ResourceKey::location).orElse(null);
        Component name = QuestText.kindName(id == null ? "structure" : id.toString());
        int dx = pos.getX() - villager.getBlockX(), dz = pos.getZ() - villager.getBlockZ();
        // Rounded the way people talk: to the nearest hundred blocks (fifty when it's close).
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        int round = dist < 500 ? 50 : 100;
        int rough = (int) Math.max(round, Math.round(dist / round) * round);
        Component dir = Component.translatable("villagequests.dir." + QuestText.direction(dx, dz));
        // Trusted friends get the exact spot.
        if (precise) return Component.translatable("villagequests.rumor.precise." + level.random.nextInt(3), name, dir, rough,
                pos.getX(), pos.getZ());
        String key = "villagequests.rumor." + level.random.nextInt(LINE_COUNT);
        return Component.translatable(key, name, dir, rough);
    }
}

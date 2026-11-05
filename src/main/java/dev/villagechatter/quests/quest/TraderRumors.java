package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.villagechatter.api.SpeechCheck;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Wandering traders always carry two or three rumors about real places (structures and remarkable biomes, the same
 * pools as villagers' {@link Rumors}). Each place is told to a player once. About half of the valuable ones (anything
 * but a village; rare biomes) are guarded: the trader names the place, then wants persuading (a check like talking a
 * guard round, much harder after a failed try) or emeralds (always works, priced by the place). Guarded rumors, once
 * given, come with the exact spot.
 */
public final class TraderRumors {
    private TraderRumors() {}

    public static final String OPTION = "trader_rumor", PERSUADE = "trader_persuade", IMPRESS = "trader_impress", BRIBE = "trader_bribe",
            BACK = "trader_back";
    private static final int MIN_RUMORS = 2, MAX_RUMORS = 3;
    private static final int MIN_BLOCKS = 150, MAX_BLOCKS = 4000;
    private static final int BIOME_MIN = 200, BIOME_MAX = 2400, BIOME_STEP = 96;
    private static final double BIOME_SHARE = 0.35;

    /** Line variants per kind of reply: villagechatter.trader.&lt;kind&gt;.&lt;n&gt;. */
    private static final Map<String, Integer> VARIANTS = Map.ofEntries(Map.entry("rumor", 6), Map.entry("secret", 5), Map.entry("guarded", 5), Map.entry("persuade_ok", 4),
            Map.entry("persuade_fail", 5), Map.entry("impress_ok", 4), Map.entry("impress_fail", 4), Map.entry("bribe_ok", 4),
            Map.entry("no_money", 4), Map.entry("none", 5), Map.entry("declined", 4), Map.entry("tapped", 4));

    /**
     * One place the trader knows. {@code key} is what goes in the player's {@link Rumors.Told} (a structure's chunk
     * middle, or a biome's 256-block cell, like villagers use). {@code value} prices a bribe: 0 = not worth guarding.
     */
    public record Entry(String id, boolean biome, int x, int z, long key, int value, boolean guarded) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Entry::id),
                Codec.BOOL.fieldOf("biome").forGetter(Entry::biome),
                Codec.INT.fieldOf("x").forGetter(Entry::x),
                Codec.INT.fieldOf("z").forGetter(Entry::z),
                Codec.LONG.fieldOf("key").forGetter(Entry::key),
                Codec.INT.fieldOf("value").forGetter(Entry::value),
                Codec.BOOL.fieldOf("guarded").forGetter(Entry::guarded)).apply(i, Entry::new));
    }

    /** What a trader knows, and how many times each player has failed to talk them round. Saved with the trader. */
    public static final class Stock {
        public static final Codec<Stock> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("stocked").forGetter(s -> s.stocked),
                Entry.CODEC.listOf().fieldOf("entries").forGetter(s -> s.entries),
                Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("fails").forGetter(s -> s.fails),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("told", Map.of()).forGetter(s -> s.told)).apply(i, Stock::new));
        public boolean stocked;
        public final List<Entry> entries;
        public final Map<String, Integer> fails;
        /** How many places this trader has told each player (Factions v2, item 9: one trader only knows so much). */
        public final Map<String, Integer> told;

        public Stock() {
            this(false, List.of(), Map.of(), Map.of());
        }

        private Stock(boolean stocked, List<Entry> entries, Map<String, Integer> fails, Map<String, Integer> told) {
            this.stocked = stocked;
            this.entries = new ArrayList<>(entries);
            this.fails = new HashMap<>(fails);
            this.told = new HashMap<>(told);
        }
    }

    public record Reply(Component line, List<String> options, Map<String, Component> labels) {}

    public static boolean enabled() {
        return QuestsModule.enabled() && QuestConfig.TRADER_RUMORS.get();
    }

    /** The trader's everyday choices with rumors in: trade, rumors, small talk, goodbye. */
    public static List<String> options(WanderingTrader trader) {
        List<String> options = new ArrayList<>();
        if (!trader.getOffers().isEmpty()) options.add(DialogueAction.TRADE);
        options.add(OPTION);
        options.add("smalltalk");
        options.add(DialogueAction.GOODBYE);
        return options;
    }

    /** The trader's rumors, picked the first time anyone asks. */
    public static Stock stock(WanderingTrader trader) {
        Stock stock = trader.getData(QuestRegistries.TRADER_RUMORS);
        if (!stock.stocked && trader.level() instanceof ServerLevel level) {
            stock.stocked = true;
            RandomSource random = level.random;
            int want = MIN_RUMORS + random.nextInt(MAX_RUMORS - MIN_RUMORS + 1);
            Set<Long> used = new HashSet<>();
            for (int tries = 0; stock.entries.size() < want && tries < want * 4; tries++) {
                Entry entry = random.nextDouble() < BIOME_SHARE ? biome(level, trader.blockPosition(), random, used)
                        : structure(level, trader.blockPosition(), random, used);
                if (entry == null) continue;
                used.add(entry.key());
                stock.entries.add(entry);
            }
            trader.setData(QuestRegistries.TRADER_RUMORS, stock);
        }
        return stock;
    }

    /** Valuable places are guarded about half the time (config). */
    static boolean guard(int value, RandomSource random) {
        return value > 0 && random.nextDouble() < QuestConfig.TRADER_GUARDED_SHARE.get();
    }

    /** Villages are worth nothing to a trader; other common sites 1, good ones 2, rare ones 3. */
    static int value(Holder<Structure> site) {
        if (site.is(Rumors.RUMOR_SITES_RARE)) return 3;
        if (site.is(Rumors.RUMOR_SITES_GOOD)) return 2;
        return site.unwrapKey().map(k -> k.location().getPath().startsWith("village")).orElse(false) ? 0 : 1;
    }

    @Nullable
    private static Entry structure(ServerLevel level, BlockPos center, RandomSource random, Set<Long> used) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> sites = new ArrayList<>();
        for (var tag : List.of(Rumors.RUMOR_SITES, Rumors.RUMOR_SITES_GOOD, Rumors.RUMOR_SITES_RARE)) {
            registry.getTag(tag).ifPresent(t -> t.forEach(sites::add));
        }
        if (sites.isEmpty()) return null;
        Holder<Structure> site = sites.get(random.nextInt(sites.size()));
        List<ChunkPos> found = StructureScan.candidates(level, site, center, MIN_BLOCKS, MAX_BLOCKS);
        found = found.stream().filter(c -> !used.contains(c.getMiddleBlockPosition(0).asLong())).toList();
        if (found.isEmpty()) return null;
        BlockPos pos = found.get(random.nextInt(Math.min(3, found.size()))).getMiddleBlockPosition(0);
        String id = site.unwrapKey().map(k -> k.location().toString()).orElse("structure");
        int value = value(site);
        return new Entry(id, false, pos.getX(), pos.getZ(), pos.asLong(), value, guard(value, random));
    }

    @Nullable
    private static Entry biome(ServerLevel level, BlockPos center, RandomSource random, Set<Long> used) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        List<BiomeRumors.Target> targets = new ArrayList<>(BiomeRumors.ALL);
        java.util.Collections.shuffle(targets, new java.util.Random(random.nextLong()));
        int angleOffset = random.nextInt(360);
        for (int r = BIOME_MIN; r <= BIOME_MAX; r += BIOME_STEP) {
            int samples = Math.max(8, (int) (2 * Math.PI * r / BIOME_STEP));
            for (int i = 0; i < samples; i++) {
                double a = Math.toRadians(angleOffset + 360.0 * i / samples);
                int x = center.getX() + (int) (Math.cos(a) * r), z = center.getZ() + (int) (Math.sin(a) * r);
                for (BiomeRumors.Target target : targets) {
                    if (!source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(target.y()), QuartPos.fromBlock(z), sampler)
                            .is(target.biome())) continue;
                    long cell = BlockPos.asLong(x >> 8, 0, z >> 8);
                    if (used.contains(cell)) continue;
                    int value = target.rare() ? 2 : 0;
                    return new Entry(target.biome().location().toString(), true, x, z, cell, value, guard(value, random));
                }
            }
        }
        return null;
    }

    /** The next place this player hasn't heard: free ones first, then the guarded ones. */
    @Nullable
    static Entry next(Stock stock, Rumors.Told told) {
        Entry guarded = null;
        for (Entry entry : stock.entries) {
            if (told.positions.contains(entry.key())) continue;
            if (!entry.guarded()) return entry;
            if (guarded == null) guarded = entry;
        }
        return guarded;
    }

    /** Whether this trader has told this player all they're going to. */
    static boolean tapped(Stock stock, String who) {
        int cap = QuestConfig.TRADER_RUMOR_CAP.get();
        return cap > 0 && stock.told.getOrDefault(who, 0) >= cap;
    }

    private static void tell(Stock stock, Rumors.Told told, String who, Entry entry) {
        told.positions.add(entry.key());
        stock.told.merge(who, 1, Integer::sum);
    }

    /** Emeralds a guarded rumor costs. */
    public static int price(Entry entry) {
        return Math.max(1, entry.value()) * QuestConfig.TRADER_BRIBE_PER_VALUE.get();
    }

    /** The chance to talk them round, after this many failed tries. */
    static double persuadeChance(int fails) {
        return QuestConfig.TRADER_PERSUADE_CHANCE.get() * Math.pow(QuestConfig.TRADER_PERSUADE_AFTER_FAIL.get(), fails);
    }

    /** With charisma (api.SpeechCheck): looking the part helps. */
    static double persuadeChance(ServerPlayer player, int fails) {
        return SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, QuestConfig.TRADER_PERSUADE_CHANCE.get(), player)
                * Math.pow(QuestConfig.TRADER_PERSUADE_AFTER_FAIL.get(), fails);
    }

    /** Impress them (Kingdom Come style): charisma alone, and it gets harder after a failed try like persuading. */
    static double impressChance(ServerPlayer player, int fails) {
        return SpeechCheck.impress(player) * Math.pow(QuestConfig.TRADER_PERSUADE_AFTER_FAIL.get(), fails);
    }

    @Nullable
    public static Reply handle(ServerPlayer player, WanderingTrader trader, String action) {
        if (!enabled()) return null;
        Stock stock = stock(trader);
        Rumors.Told told = player.getData(QuestRegistries.RUMORS_TOLD);
        RandomSource random = player.getRandom();
        String who = player.getUUID().toString();
        Entry next = tapped(stock, who) ? null : next(stock, told);
        switch (action) {
            case OPTION -> {
                if (next == null) return base(trader, variant(tapped(stock, who) && next(stock, told) != null ? "tapped" : "none", random));
                if (!next.guarded()) {
                    tell(stock, told, who, next);
                    return base(trader, place(trader, next, false, random));
                }
                return guarded(player, trader, next, variant("guarded", random, name(next)));
            }
            case PERSUADE, IMPRESS -> {
                if (next == null || !next.guarded()) return base(trader, variant("none", random));
                int fails = stock.fails.getOrDefault(who, 0);
                boolean impress = action.equals(IMPRESS);
                if (dev.villagechatter.life.Speech.roll(player, impress ? impressChance(player, fails) : persuadeChance(player, fails))) {
                    tell(stock, told, who, next);
                    return base(trader, variant(impress ? "impress_ok" : "persuade_ok", random).copy().append(" ")
                            .append(place(trader, next, true, random)));
                }
                stock.fails.put(who, fails + 1);
                return guarded(player, trader, next, variant(impress ? "impress_fail" : "persuade_fail", random));
            }
            case BRIBE -> {
                if (next == null || !next.guarded()) return base(trader, variant("none", random));
                int cost = price(next);
                if (emeralds(player) < cost) return guarded(player, trader, next, variant("no_money", random, cost));
                takeEmeralds(player, cost);
                tell(stock, told, who, next);
                return base(trader, variant("bribe_ok", random).copy().append(" ").append(place(trader, next, true, random)));
            }
            case BACK -> {
                return base(trader, variant("declined", random));
            }
            default -> {
                return null;
            }
        }
    }

    private static Reply base(WanderingTrader trader, Component line) {
        return new Reply(line, options(trader), Map.of());
    }

    private static Reply guarded(ServerPlayer player, WanderingTrader trader, Entry entry, Component line) {
        List<String> options = new ArrayList<>(List.of(PERSUADE, IMPRESS, BRIBE, BACK));
        if (!trader.getOffers().isEmpty()) options.add(DialogueAction.TRADE);
        options.add(DialogueAction.GOODBYE);
        int fails = trader.getData(QuestRegistries.TRADER_RUMORS).fails.getOrDefault(player.getUUID().toString(), 0);
        return new Reply(line, options, Map.of(
                PERSUADE, dev.villagechatter.api.SpeechCheck.label(dev.villagechatter.api.SpeechCheck.Skill.PERSUADE,
                        persuadeChance(player, fails), Component.translatable("villagechatter.option." + PERSUADE)),
                IMPRESS, SpeechCheck.label(SpeechCheck.Skill.IMPRESS, impressChance(player, fails),
                        Component.translatable("villagechatter.option." + IMPRESS)),
                BRIBE, dev.villagechatter.api.SpeechCheck.label(dev.villagechatter.api.SpeechCheck.Skill.BRIBE, 1,
                        Component.translatable("villagechatter.option.trader_bribe.cost", price(entry)))));
    }

    static Component name(Entry entry) {
        if (!entry.biome()) return QuestText.kindName(entry.id());
        ResourceLocation id = ResourceLocation.parse(entry.id());
        return Component.translatable("biome." + id.getNamespace() + "." + id.getPath());
    }

    /** "A mansion, north-west, about 2300 blocks", or with the exact spot once paid for. */
    private static Component place(WanderingTrader trader, Entry entry, boolean precise, RandomSource random) {
        int dx = entry.x() - trader.getBlockX(), dz = entry.z() - trader.getBlockZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        int round = dist < 500 ? 50 : 100;
        int rough = (int) Math.max(round, Math.round(dist / round) * round);
        Component dir = Component.translatable("villagequests.dir." + QuestText.direction(dx, dz));
        return precise ? variant("secret", random, name(entry), dir, rough, entry.x(), entry.z())
                : variant("rumor", random, name(entry), dir, rough);
    }

    private static Component variant(String kind, RandomSource random, Object... args) {
        return Component.translatable("villagechatter.trader." + kind + "." + random.nextInt(VARIANTS.get(kind)), args);
    }

    private static int emeralds(ServerPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(Items.EMERALD)) count += stack.getCount();
        return count;
    }

    private static void takeEmeralds(ServerPlayer player, int count) {
        for (ItemStack stack : player.getInventory().items) {
            if (count <= 0) return;
            if (!stack.is(Items.EMERALD)) continue;
            int take = Math.min(count, stack.getCount());
            stack.shrink(take);
            count -= take;
        }
    }

    /** For GameTests: put a known place in a trader's stock. */
    static void add(WanderingTrader trader, Entry entry) {
        Stock stock = trader.getData(QuestRegistries.TRADER_RUMORS);
        stock.stocked = true;
        stock.entries.add(entry);
        trader.setData(QuestRegistries.TRADER_RUMORS, stock);
    }
}

package dev.villagechatter.quests;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class QuestConfig {
    private QuestConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Quests module on/off. Off = dialogue only: no quest offers, quest talk, guardians or markers.",
                    "Quests you already have are kept and continue when you switch it back on.")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue OFFER_CHANCE_PER_DAY = BUILDER
            .comment("Chance per in-game day that a villager near a player (and off cooldown) comes up with a problem.")
            .defineInRange("offerChancePerDay", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_OFFERS_PER_VILLAGE = BUILDER
            .comment("At most this many villagers within 64 blocks of each other have a pending quest at once.")
            .defineInRange("maxOffersPerVillage", 2, 0, 64);

    public static final ModConfigSpec.DoubleValue OFFER_COOLDOWN_DAYS = BUILDER
            .comment("In-game days before a villager can ask for help again after someone took their quest.")
            .defineInRange("villagerOfferCooldownDays", 3.0, 0.0, 365.0);

    public static final ModConfigSpec.IntValue MAX_ACTIVE_QUESTS = BUILDER
            .comment("How many quests one player can have running at once.")
            .defineInRange("maxActiveQuestsPerPlayer", 3, 1, 32);

    public static final ModConfigSpec.DoubleValue DEFAULT_DEADLINE_DAYS = BUILDER
            .comment("Deadline in in-game days for quests whose JSON doesn't set deadline_days.")
            .defineInRange("defaultDeadlineDays", 5.0, 0.1, 365.0);

    public static final ModConfigSpec.IntValue SEARCH_DISTANCE_MIN = BUILDER
            .comment("Quest targets are never closer than this to the villager (clamps the JSON min_distance).")
            .defineInRange("searchDistanceMin", 48, 0, 4096);

    public static final ModConfigSpec.IntValue SEARCH_DISTANCE_MAX = BUILDER
            .comment("Quest targets are never further than this from the villager (clamps the JSON max_distance).")
            .defineInRange("searchDistanceMax", 384, 16, 8192);

    public static final ModConfigSpec.IntValue CAVE_SEARCH_CHUNKS = BUILDER
            .comment("How many chunks to look in for a cave when a quest is accepted. Each one may generate a chunk once.")
            .defineInRange("caveSearchChunks", 6, 1, 64);

    public static final ModConfigSpec.IntValue GUARDIAN_SPAWN_DISTANCE = BUILDER
            .comment("The guardian appears when the quest owner comes this close (blocks) to the target.")
            .defineInRange("guardianSpawnDistance", 32, 8, 128);

    public static final ModConfigSpec.DoubleValue GUARDIAN_DESPAWN_MINUTES = BUILDER
            .comment("A guardian whose owner has been away this long (real minutes) leaves. It comes back next time.")
            .defineInRange("guardianDespawnMinutes", 5.0, 0.5, 120.0);

    public static final ModConfigSpec.DoubleValue REPUTATION_MULTIPLIER = BUILDER
            .comment("Multiplies every reputation change from quests.")
            .defineInRange("reputationMultiplier", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue SECURITY_MIN_TRUST = BUILDER
            .comment("Trust a player needs with a guard (the guard's reputation plus quest goodwill) before that guard",
                    "asks them to fortify the village (security requests: place defensive blocks). Guard trust tier 1.")
            .defineInRange("securityRequestMinTrust", 6.0, -100.0, 1000.0);

    public static final ModConfigSpec.DoubleValue GOLEM_QUEST_MIN_TRUST = BUILDER
            .comment("Standing a player needs with an iron golem (its quest goodwill plus the village's shared golem standing)",
                    "before it asks them to bring home a lost villager.")
            .defineInRange("golemQuestMinTrust", 10.0, -100.0, 1000.0);

    public static final ModConfigSpec.BooleanValue STANDALONE_RIGHT_CLICK = BUILDER
            .comment("When villager dialogue is switched off (talkToVillagers / talkToBabyVillagers = false), quests are",
                    "offered with chat buttons instead. Sneak + right-click always trades as normal.")
            .define("chatPromptsWithoutDialogue", true);

    public static final ModConfigSpec.DoubleValue SMALL_TALK_SURPRISE_CHANCE = BUILDER
            .comment("Chance that a fresh small-talk answer is a rumor about a real structure out there, or the villager",
                    "remembering they could use your help (a quest offer).")
            .defineInRange("smallTalkSurpriseChance", 0.05, 0.0, 1.0);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_PROFESSIONS = BUILDER
            .comment("Villager professions that never hand out quests.")
            .defineListAllowEmpty("excludedProfessions", List.of("bountiful_npc:receptionist"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue TRADER_RUMORS = BUILDER
            .comment("Wandering traders carry 2-3 rumors about real places (structures and remarkable biomes); ask them.")
            .define("traderRumors", true);

    public static final ModConfigSpec.DoubleValue TRADER_GUARDED_SHARE = BUILDER
            .comment("Share of a trader's valuable rumors (anything but villages, rare biomes) they won't give away for free.")
            .defineInRange("traderGuardedShare", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue TRADER_PERSUADE_CHANCE = BUILDER
            .comment("Chance to talk a trader into a guarded rumor on the first try.")
            .defineInRange("traderPersuadeChance", 0.35, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue TRADER_PERSUADE_AFTER_FAIL = BUILDER
            .comment("Each failed try multiplies that chance by this (the trader gets harder to talk round).")
            .defineInRange("traderPersuadeAfterFail", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue TRADER_RUMOR_CAP = BUILDER
            .comment("Most places one wandering trader tells one player, however you ask (free, talked round or paid).",
                    "The next trader through knows other roads. 0 = no cap.")
            .defineInRange("traderRumorCap", 3, 0, 64);

    public static final ModConfigSpec.IntValue TRADER_BRIBE_PER_VALUE = BUILDER
            .comment("Emeralds a guarded rumor costs per point of value (common sites 1, temples and monuments 2,",
                    "mansions and ancient cities 3, rare biomes 2). Paying always works.")
            .defineInRange("traderBribePerValue", 5, 1, 64);

    public static final ModConfigSpec SPEC = BUILDER.build();
}

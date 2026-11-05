package dev.villagechatter;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChatterConfig {
    private ChatterConfig() {}

    /**
     * Babble voice sets. basePitch and minGapMs control how each set is played back in time with the text;
     * keep them in sync with VOICE_PLAYBACK in tools/sound_gen/generate.py so the previews match the game.
     */
    /** The dialogue box's look (client): textures and font under textures/gui/dialogue/&lt;id&gt;. */
    public enum DialogueTheme {
        CLASSIC, TERMINAL, NORTHERN, BLOCKY, STORYBOOK, BRONZE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The dialogue font: the theme's own, Minecraft's pixel font, or the smooth rounded one (Nunito). */
    public enum DialogueFont { THEME, MINECRAFT, SMOOTH }

    public enum VoiceStyle {
        MURMUR("murmur", 1.0F, 110),
        NONE("none", 1.0F, 0);

        public final String id;
        public final float basePitch;
        public final int minGapMs;

        VoiceStyle(String id, float basePitch, int minGapMs) {
            this.id = id;
            this.basePitch = basePitch;
            this.minGapMs = minGapMs;
        }
    }

    /** What villagers sound like when they're just going about their day (not in a conversation with you). */
    public enum AmbientVoice { VILLAGERESE, VANILLA, BOTH }

    // ---- Common (both sides) ----
    private static final ModConfigSpec.Builder COMMON = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SNEAK_TO_SKIP = COMMON
            .comment("Sneak + right-click skips the dialogue and goes straight to the villager's normal interaction (trading).")
            .define("sneakToSkip", true);

    public static final ModConfigSpec.BooleanValue ALL_VILLAGER_TYPES = COMMON
            .comment("Talk to every villager-like trader (anything extending AbstractVillager), not only entities in the #villagechatter:talkable tag.")
            .define("talkToAllVillagerTypes", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_PROFESSIONS = COMMON
            .comment("Villager professions that keep their normal right-click behaviour. Use this for NPCs another mod already gives dialogue to.")
            .defineListAllowEmpty("excludedProfessions", List.of("bountiful_npc:receptionist"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_ENTITIES = COMMON
            .comment("Entity types that never open a dialogue, even if tagged, e.g. \"minecraft:wandering_trader\".")
            .defineListAllowEmpty("excludedEntities", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue TALK_TO_VILLAGERS = COMMON
            .comment("Who you can talk to. Turn any of these off to keep that creature's normal right-click.")
            .define("talkToVillagers", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_BABY_VILLAGERS = COMMON.define("talkToBabyVillagers", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_WANDERING_TRADERS = COMMON.define("talkToWanderingTraders", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_GUARDS = COMMON
            .comment("Guards from mods like Guard Villagers (anything in #villagechatter:talkable with \"guard\" in its id).")
            .define("talkToGuards", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_IRON_GOLEMS = COMMON.define("talkToIronGolems", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_WOLVES = COMMON
            .comment("Pets: your own tamed ones. Standing right-click talks; crouch + right-click keeps the classic sit/follow",
                    "(and other pet mods' interactions). Feeding, dyeing collars, leads and name tags still work as usual.")
            .define("talkToWolves", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_CATS = COMMON.define("talkToCats", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_PARROTS = COMMON.define("talkToParrots", true);
    public static final ModConfigSpec.BooleanValue TALK_TO_DOGGY_TALENTS = COMMON
            .comment("Doggy Talents Next dogs: standing right-click talks, crouch + right-click keeps Doggy Talents' own interaction.")
            .define("talkToDoggyTalentsDogs", true);

    public static final ModConfigSpec.IntValue SIGHTING_RADIUS = COMMON
            .comment("How far (blocks) naturalist villagers know about bee nests, flocks and game around them.")
            .defineInRange("sightingRadius", 128, 16, 512);

    public static final ModConfigSpec.BooleanValue AMBIENT_CHATTER = COMMON
            .comment("Living villages: villagers stop to chat with each other in passing, and much more at the bell meet-up.")
            .define("ambientChatter", true);
    public static final ModConfigSpec.BooleanValue VILLAGE_REACTIONS = COMMON
            .comment("Curious onlookers while you build, and a reaction when you break a villager's bed in front of them.")
            .define("villageReactions", true);
    public static final ModConfigSpec.BooleanValue VILLAGER_KINDNESS = COMMON
            .comment("Villagers who like you help out now and then: a mason's spare stone, food when you're badly hurt,",
                    "a close friend's surprise gift. All rare, all with cooldowns.")
            .define("villagerKindness", true);
    public static final ModConfigSpec.BooleanValue CHEST_REPUTATION = COMMON
            .comment("Taking from village chests you didn't place costs a tiny bit of village goodwill per item.")
            .define("chestReputation", true);
    public static final ModConfigSpec.BooleanValue GUARD_ESCORT = COMMON
            .comment("In a village that distrusts you, a guard shadows you until you leave (closer the worse it is).")
            .define("guardEscort", true);

    public static final ModConfigSpec.EnumValue<AmbientVoice> AMBIENT_VOICE = COMMON
            .comment("How villagers sound on their own (chatting with each other, reacting, idle mumbling):",
                    "VILLAGERESE = only this mod's babble, the vanilla 'hmm' sounds are muted;",
                    "VANILLA = only the vanilla villager sounds (their chats use them too);",
                    "BOTH = both, with a built-in pause so one villager never does both at once.",
                    "Conversations with you always use the dialogue voice (see the client voiceStyle).")
            .defineEnum("ambientVoice", AmbientVoice.BOTH);

    public static final ModConfigSpec.IntValue GUARD_GEAR_TRUST = COMMON
            .comment("Standing a player needs with a particular guard before that guard lets them into their inventory",
                    "(Guard Villagers' gear screen). Standing = the guard's own reputation plus goodwill from quests;",
                    "20 is 'Friend'. The Hero of the Village is always trusted.")
            .defineInRange("guardGearTrust", 20, -100, 500);

    public static final ModConfigSpec.BooleanValue LAW_AND_ORDER = COMMON
            .comment("Crimes have consequences: witnesses report theft, assault and worse to guards and golems, who confront you.")
            .define("lawAndOrder", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECURITY_WORDS = COMMON
            .comment("A placed block counts as village security when its id contains one of these words (so modded",
                    "portcullises, turrets and the like count with no explicit support), or it's a vanilla wall, fence, gate, door or trapdoor.")
            .defineListAllowEmpty("securityWords", List.of("wall", "fence", "gate", "door", "portcullis", "trap", "turret",
                    "cannon", "barricade", "palisade", "spike", "rampart", "battlement"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECURITY_EXCLUDED = COMMON
            .comment("Ids containing any of these never count as security (decorations that happen to match a word above).")
            .defineListAllowEmpty("securityExcluded", List.of("wall_torch", "wall_sign", "wall_hanging_sign", "wall_banner",
                    "wall_head", "wall_skull", "trapped_chest", "wallpaper", "wall_fan", "trapdoor_fan"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.DoubleValue MAX_TALK_DISTANCE = COMMON
            .comment("Conversation ends if you walk further than this many blocks away.")
            .defineInRange("maxTalkDistance", 6.0, 2.0, 32.0);

    public static final ModConfigSpec.BooleanValue NEWBORN_INHERITS = COMMON
            .comment("A villager born from breeding starts out with its parents' average opinion of each player (or its",
                    "village's, without parents), instead of a blank slate.")
            .define("newbornInheritsReputation", true);

    public static final ModConfigSpec.BooleanValue JOB_ASSIGNMENT = COMMON
            .comment("\"About your job\": players can give an adult villager a profession and pick its workstation.")
            .define("jobAssignment", true);

    public static final ModConfigSpec.BooleanValue RETRAIN_TRADED = COMMON
            .comment("Villagers who have already traded (vanilla locks their job) can still be given a new one; their",
                    "trades and level start over. Off: their job stays as it is.")
            .define("retrainTradedVillagers", true);

    public static final ModConfigSpec.BooleanValue KEEP_ASSIGNED_JOB = COMMON
            .comment("A villager whose job you assigned keeps it when its workstation is broken, and looks for another",
                    "free one of the same kind (it still needs one nearby to restock).")
            .define("keepAssignedJob", true);

    public static final ModConfigSpec.IntValue JOB_PICK_RADIUS = COMMON
            .comment("How far from the villager (in blocks) workstations are listed and highlighted.")
            .defineInRange("jobPickRadius", 32, 8, 64);

    public static final ModConfigSpec.IntValue JOB_PICK_SECONDS = COMMON
            .comment("Picking a workstation in the world ends after this many seconds.")
            .defineInRange("jobPickSeconds", 60, 10, 300);

    // Factions: villages as a whole, and the pillagers.
    public static final ModConfigSpec.BooleanValue FACTIONS = COMMON
            .comment("Two faction standings: villages as a whole (a summary of the villages you know plus a slowly fading",
                    "'word on the road') and the pillagers (their own meter). Shown in the quest journal.")
            .define("factions", true);
    public static final ModConfigSpec.IntValue PILLAGER_START = COMMON
            .comment("Where everyone starts with the pillagers (-100 to 100).")
            .defineInRange("pillagerStandingStart", -50, -100, 100);
    public static final ModConfigSpec.IntValue PILLAGER_WARY = COMMON
            .comment("Pillager standing tiers: below 'wary' they attack on sight (Hated); wary = talk first, tolls.")
            .defineInRange("pillagerWaryAt", -40, -100, 100);
    public static final ModConfigSpec.IntValue PILLAGER_TOLERATED = COMMON
            .comment("Tolerated: pillagers outside a raid let you pass, and warn you if you draw a weapon.")
            .defineInRange("pillagerToleratedAt", 0, -100, 100);
    public static final ModConfigSpec.IntValue PILLAGER_ALLY = COMMON
            .comment("Ally: highwaymen wave you through, outposts let you in.")
            .defineInRange("pillagerAllyAt", 40, -100, 100);
    public static final ModConfigSpec.IntValue VILLAGE_HOSTILE_BELOW = COMMON
            .comment("Village standing below this and guards attack you on sight (caravan guards too).")
            .defineInRange("villageGuardsAttackBelow", -60, -100, 0);
    public static final ModConfigSpec.DoubleValue VILLAGE_INHERIT = COMMON
            .comment("A village you've never been to starts at this share of your village standing (its reputation travels).")
            .defineInRange("newVillageInheritShare", 0.5, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue VILLAGE_RECENCY_DAYS = COMMON
            .comment("Villages you haven't seen in this many days count half as much toward your village standing.")
            .defineInRange("villageRecencyHalfLifeDays", 30.0, 1.0, 1000.0);
    public static final ModConfigSpec.DoubleValue ROAMING_HALF_LIFE_DAYS = COMMON
            .comment("Word on the road (deeds away from any village, like caravan crimes) fades by half in this many days.")
            .defineInRange("roamingHalfLifeDays", 7.0, 0.5, 1000.0);
    public static final ModConfigSpec.DoubleValue CRIME_WITNESS_WEIGHT = COMMON
            .comment("Each extra witness makes a crime's reputation hit this much bigger (0.15 = +15%, up to double).")
            .defineInRange("crimeWitnessWeight", 0.15, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue CRIME_STANDING_WEIGHT = COMMON
            .comment("How much your standing matters: high standing cushions petty crimes, and murders hit harder the higher",
                    "you stood. 0 = off.")
            .defineInRange("crimeStandingWeight", 1.0, 0.0, 2.0);

    // Witnesses
    public static final ModConfigSpec.IntValue WITNESS_MEMORY_TICKS = COMMON
            .comment("How long (in ticks; 24000 = one Minecraft day) a witness's memory of a crime stays pending. If every",
                    "witness dies before then and nobody reported it, it's as if it never happened. After that, or once",
                    "reported, it's permanent.")
            .defineInRange("witnessMemoryTicks", 24000, 0, 240000);
    public static final ModConfigSpec.DoubleValue WITNESS_STOP_CHANCE = COMMON
            .comment("Chance a witness running to report stops when you right-click them (otherwise they snap at you and keep running).")
            .defineInRange("witnessStopChance", 0.5, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue WITNESS_BRIBE_PER_HIT = COMMON
            .comment("Emeralds a witness wants to keep quiet, per point of the crime's reputation hit (theft 4, murder 40).")
            .defineInRange("witnessBribePerHit", 0.5, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue WITNESS_PERSUADE_CHANCE = COMMON
            .comment("Chance 'it was an honest mistake' works on a witness (petty crimes; serious ones are harder).")
            .defineInRange("witnessPersuadeChance", 0.45, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue WITNESS_INTIMIDATE_CHANCE = COMMON
            .comment("Base chance intimidation scares a witness quiet; your armor, a weapon in hand and a bad reputation add to it.")
            .defineInRange("witnessIntimidateChance", 0.3, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue WITNESS_RETRY_FACTOR = COMMON
            .comment("Each failed check with a witness multiplies the chance of the remaining ones by this.")
            .defineInRange("witnessRetryFactor", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue SPEECH_CHECK_PERCENT = COMMON
            .comment("Speech checks ([Persuade], [Bribe], [Intimidate]...) show the exact chance (\"[Persuade 45%]\") instead",
                    "of a difficulty color (yellow easy, orange medium, red hard).")
            .define("speechCheckShowsPercent", false);
    public static final ModConfigSpec.DoubleValue FEUD_GAIN = COMMON
            .comment("Villagers, pillagers and piglins dislike each other: killing one side's people gains this much with",
                    "the other sides (piglin edges only; villages and pillagers already swing against each other).")
            .defineInRange("feudGain", 0.3, 0.0, 10.0);
    public static final ModConfigSpec.BooleanValue SABOTAGE_JOBS = COMMON
            .comment("Villagers, piglins and pillager captains offer sabotage jobs against the other two sides.")
            .define("sabotageJobs", true);
    public static final ModConfigSpec.DoubleValue SABOTAGE_REWARD = COMMON
            .comment("Standing with whoever asked when you report a sabotage job done.")
            .defineInRange("sabotageReward", 6.0, 0.0, 100.0);
    public static final ModConfigSpec.DoubleValue SABOTAGE_DECLINE_LOSS = COMMON
            .comment("Standing lost with whoever asked when you turn a sabotage job down.")
            .defineInRange("sabotageDeclineLoss", 1.0, 0.0, 100.0);
    public static final ModConfigSpec.IntValue SABOTAGE_DAYS = COMMON
            .comment("Days (Minecraft) a sabotage job stays open before it's off.")
            .defineInRange("sabotageDays", 3, 1, 30);
    public static final ModConfigSpec.DoubleValue WRONG_GIFT_LOSS = COMMON
            .comment("Goodwill (or standing) lost for the wrong gift: rotten food to a villager, sweets to a pillager.")
            .defineInRange("wrongGiftLoss", 0.5, 0.0, 20.0);
    public static final ModConfigSpec.DoubleValue COOKED_GIFT_BONUS = COMMON
            .comment("Cooked food as a gift is worth this many times raw food to villagers.")
            .defineInRange("cookedGiftBonus", 1.25, 1.0, 5.0);
    public static final ModConfigSpec.DoubleValue PILLAGER_GIFT_GAIN = COMMON
            .comment("Pillager standing for a gift of meat (gear counts half again).")
            .defineInRange("pillagerGiftGain", 1.0, 0.0, 20.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_GOLD_QUEST_GAIN = COMMON
            .comment("Piglin standing per gold ingot handed over for a piglin's 'bring us gold' quest.")
            .defineInRange("piglinGoldQuestGain", 0.5, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_HEIRLOOM_GAIN = COMMON
            .comment("Piglin standing for bringing back the heirloom from a village.")
            .defineInRange("piglinHeirloomGain", 10.0, 0.0, 100.0);
    // Renamed from bannerLoyalty (a placeholder that defaulted off) so existing configs pick up the new default.
    public static final ModConfigSpec.BooleanValue BANNER_LOYALTY = COMMON
            .comment("Wearing the pillagers' colours (an ominous shield in either hand, the ominous banner on your head,",
                    "a helmet crested with it, or it in a Curios head slot) makes outpost pillagers treat you as at least",
                    "Tolerated. Patrols and raids still go by your true standing.")
            .define("bannerColours", true);
    public static final ModConfigSpec.DoubleValue BANNER_UNSEEN_LOSS = COMMON
            .comment("Pillager standing lost when a pillager sees you below Tolerated without their colours (it attacks).")
            .defineInRange("bannerUnseenLoss", 2.0, 0.0, 100.0);
    public static final ModConfigSpec.BooleanValue KIN_CONFRONTS = COMMON
            .comment("Hit a villager, and a while later one of their neighbours finds you and wants an apology.")
            .define("kinConfronts", true);
    public static final ModConfigSpec.BooleanValue FRIENDLY_FIRE = COMMON
            .comment("Hit a guard, your hired guard or an allied pillager, and the first hits are taken as accidents (with a",
                    "warning). One more within a minute and they treat it as an attack.")
            .define("friendlyFire", true);
    public static final ModConfigSpec.IntValue FRIENDLY_HITS = COMMON
            .comment("How many hits an ally forgives before turning on you.")
            .defineInRange("friendlyHits", 2, 1, 5);
    public static final ModConfigSpec.BooleanValue BRAWL_CHEERS = COMMON
            .comment("Villagers who see you in a fist fight (or fighting another player) in their village cheer it on.")
            .define("brawlCheers", true);
    public static final ModConfigSpec.BooleanValue WATCH_CALLS = COMMON
            .comment("Guards call the hour at dawn, noon, dusk and midnight, like a town watch.")
            .define("watchCalls", true);
    public static final ModConfigSpec.BooleanValue GEAR_REQUESTS = COMMON
            .comment("Drop armor or a weapon near a villager, and they ask whether they can have it.")
            .define("gearRequests", true);
    public static final ModConfigSpec.BooleanValue RAID_OUTCOMES = COMMON
            .comment("Pillagers react to how a raid you were near went: praise if you sided with them and they won, scorn if",
                    "they lost, and if you fought them and they won, their captain makes you surrender or fight on.")
            .define("raidOutcomes", true);
    public static final ModConfigSpec.DoubleValue RAID_OUTCOME_GAIN = COMMON
            .comment("Pillager standing gained when a raid you sided with is won.")
            .defineInRange("raidOutcomeGain", 5.0, 0.0, 100.0);
    public static final ModConfigSpec.DoubleValue RAID_OUTCOME_LOSS = COMMON
            .comment("Pillager standing lost when a raid you sided with is lost.")
            .defineInRange("raidOutcomeLoss", 5.0, 0.0, 100.0);
    public static final ModConfigSpec.IntValue TOLL_ITEMS_WEIGHT = COMMON
            .comment("Surrendering after a lost raid: how often the captain demands 1-3 random things from your inventory (a weight).")
            .defineInRange("tollItemsWeight", 1, 0, 100);
    public static final ModConfigSpec.IntValue TOLL_ARMOR_WEIGHT = COMMON
            .comment("Surrendering after a lost raid: how often the captain demands the armor you're wearing (a weight).")
            .defineInRange("tollArmorWeight", 1, 0, 100);
    public static final ModConfigSpec.IntValue TOLL_WEAPON_WEIGHT = COMMON
            .comment("Surrendering after a lost raid: how often the captain demands the weapon in your hand (a weight).")
            .defineInRange("tollWeaponWeight", 1, 0, 100);
    public static final ModConfigSpec.DoubleValue BANNER_VILLAGE_LOSS = COMMON
            .comment("Village standing lost when a villager sees you wearing the pillagers' colours.")
            .defineInRange("bannerVillageLoss", 3.0, 0.0, 100.0);
    public static final ModConfigSpec.IntValue BANNER_SIGHTING_COOLDOWN = COMMON
            .comment("Ticks before the same outpost, patrol or village can cost you again for a sighting (24000 = a day).")
            .defineInRange("bannerSightingCooldown", 24000, 20, 2400000);
    public static final ModConfigSpec.BooleanValue HERO_ANGERS_PILLAGERS = COMMON
            .comment("While you're a Hero of the Village, pillagers are hostile whatever your standing with them.")
            .define("heroAngersPillagers", true);
    public static final ModConfigSpec.DoubleValue WIPEOUT_LOSS = COMMON
            .comment("Village standing lost when you wipe out a village yourself, witnesses or not.")
            .defineInRange("wipeoutLoss", 30.0, 0.0, 200.0);
    public static final ModConfigSpec.IntValue WIPEOUT_MORALE = COMMON
            .comment("Gossip (minor negative) every villager near a village you wiped out holds against you.")
            .defineInRange("wipeoutMorale", 10, 0, 100);
    public static final ModConfigSpec.IntValue WIPEOUT_RADIUS = COMMON
            .comment("How far (blocks) the news of a wiped-out village dampens nearby villages' morale.")
            .defineInRange("wipeoutRadius", 512, 64, 4096);
    public static final ModConfigSpec.BooleanValue TALK_TO_PILLAGERS = COMMON
            .comment("Pillagers and vindicators who tolerate you can be talked to. Captains (banner wearers) talk and give",
                    "jobs; the rest just say a line.")
            .define("talkToPillagers", true);
    public static final ModConfigSpec.IntValue RAID_RANGE = COMMON
            .comment("How far (blocks) a pillager captain looks for a village to send you raiding.")
            .defineInRange("raidRange", 256, 32, 1024);
    public static final ModConfigSpec.IntValue RAID_RADIUS = COMMON
            .comment("How close to the raided village's bell a kill must be to count for a raid job.")
            .defineInRange("raidRadius", 96, 16, 512);
    public static final ModConfigSpec.DoubleValue RAID_KILL_GAIN = COMMON
            .comment("Pillager standing per raid kill a pillager sees.")
            .defineInRange("raidKillGain", 3.0, 0.0, 100.0);
    public static final ModConfigSpec.DoubleValue RAID_KILL_LOSS = COMMON
            .comment("Village standing lost per raid kill a pillager sees (witnesses or not).")
            .defineInRange("raidKillLoss", 8.0, 0.0, 100.0);
    public static final ModConfigSpec.IntValue PIGLIN_ZOMBIFY_TICKS = COMMON
            .comment("How long (ticks) a piglin lasts out of the Nether before it zombifies (vanilla: 300). Standing on",
                    "Nether ground (netherrack, nylium, soul soil, basalt...) anywhere winds it back to the full time. 24000 = one Minecraft day.")
            .defineInRange("piglinZombifyTicks", 24000, 300, 2400000);
    public static final ModConfigSpec.DoubleValue PIGLIN_SEEK_FRACTION = COMMON
            .comment("When this share of a piglin's time out of the Nether is left, it walks to Nether ground to refresh.")
            .defineInRange("piglinSeekFraction", 0.25, 0.0, 1.0);
    public static final ModConfigSpec.IntValue PIGLIN_SEEK_RANGE = COMMON
            .comment("How far (blocks) a piglin looks for Nether ground (tag villagechatter:piglin_safe) when its time runs low.")
            .defineInRange("piglinSeekRange", 16, 0, 48);
    public static final ModConfigSpec.DoubleValue SPEECH_GAIN = COMMON
            .comment("How fast the Speech skill (0-100) grows from speech checks and trades (1 = normal, 0 = never).")
            .defineInRange("speechGain", 1.0, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue SPEECH_CHECK_BONUS = COMMON
            .comment("How much likelier speech checks (all but bribes) are at Speech 100: 0.5 = half as likely again.")
            .defineInRange("speechCheckBonus", 0.5, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue SPEECH_DISCOUNT = COMMON
            .comment("Share taken off villagers' prices at Speech 100 (less below).")
            .defineInRange("speechDiscount", 0.1, 0.0, 0.5);
    public static final ModConfigSpec.DoubleValue HERO_CHECK_BONUS = COMMON
            .comment("How much likelier speech checks (all but bribes) are while you're a Hero of the Village.")
            .defineInRange("heroCheckBonus", 0.3, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue WITNESS_BRIBE_CHANCE = COMMON
            .comment("Chance a witness takes your money and keeps quiet (serious crimes are harder).")
            .defineInRange("witnessBribeChance", 0.75, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue CHARITY_BLESSING = COMMON
            .comment("Giving a nitwit an emerald or food earns a blessing: speech checks (except bribes) get this much better.")
            .defineInRange("charityBlessing", 0.1, 0.0, 1.0);
    public static final ModConfigSpec.IntValue CHARITY_BLESSING_SECONDS = COMMON
            .comment("How long a beggar's blessing lasts, in seconds. A new gift restarts it; blessings don't stack.")
            .defineInRange("charityBlessingSeconds", 600, 0, 72000);
    public static final ModConfigSpec.IntValue CHARITY_PER_DAY = COMMON
            .comment("Gifts to beggars a day that still raise your standing (blessings don't stop).")
            .defineInRange("charityPerDay", 3, 0, 100);

    public static final ModConfigSpec.BooleanValue TALK_TO_PIGLINS = COMMON
            .comment("Piglins can be talked to. Right-clicking with gold in hand, sneaking, or dropping gold still barters",
                    "the vanilla way.")
            .define("talkToPiglins", true);
    public static final ModConfigSpec.IntValue PIGLIN_START = COMMON
            .comment("Where everyone starts with the piglins (-100 to 100).")
            .defineInRange("piglinStandingStart", 0, -100, 100);
    public static final ModConfigSpec.IntValue PIGLIN_LIKED_AT = COMMON
            .comment("Piglin standing at or above this: friendlier talk (and the barter bonus, if on).")
            .defineInRange("piglinLikedAt", 30, -100, 100);
    public static final ModConfigSpec.IntValue PIGLIN_DISLIKED_AT = COMMON
            .comment("Piglin standing at or below this: a short fuse (one question too many and they turn on you).")
            .defineInRange("piglinDislikedAt", -30, -100, 100);
    public static final ModConfigSpec.DoubleValue PIGLIN_BARTER_GAIN = COMMON
            .comment("Piglin standing gained per gold ingot bartered (by dialogue or the vanilla way).")
            .defineInRange("piglinBarterGain", 0.25, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_MINE_LOSS = COMMON
            .comment("Piglin standing lost for mining gold they guard, once a witness tells another piglin.")
            .defineInRange("piglinMineLoss", 1.0, 0.0, 50.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_CHEST_LOSS = COMMON
            .comment("Piglin standing lost for opening a chest they guard, once a witness tells another piglin.")
            .defineInRange("piglinChestLoss", 2.0, 0.0, 50.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_HURT_LOSS = COMMON
            .comment("Piglin standing lost for hitting a piglin, once a witness tells another piglin.")
            .defineInRange("piglinHurtLoss", 2.0, 0.0, 50.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_KILL_LOSS = COMMON
            .comment("Piglin standing lost for killing a piglin, once a witness tells another piglin.")
            .defineInRange("piglinKillLoss", 5.0, 0.0, 50.0);
    public static final ModConfigSpec.IntValue PIGLIN_ADMIRE_TICKS = COMMON
            .comment("How long a piglin looks your gold over before handing over what it trades (ticks).")
            .defineInRange("piglinAdmireTicks", 40, 0, 400);
    public static final ModConfigSpec.BooleanValue PIGLIN_BARTER_BONUS = COMMON
            .comment("Piglins who like you sometimes throw in an extra barter roll.")
            .define("piglinBarterBonus", false);
    public static final ModConfigSpec.DoubleValue PIGLIN_BARTER_BONUS_CHANCE = COMMON
            .comment("With the bonus on and good standing: the chance per ingot of an extra roll.")
            .defineInRange("piglinBarterBonusChance", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue PIGLIN_ENEMY_GAIN = COMMON
            .comment("Piglin standing gained for killing a wither skeleton where a piglin can see.")
            .defineInRange("piglinEnemyKillGain", 1.0, 0.0, 50.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_WITHER_GAIN = COMMON
            .comment("Piglin standing gained for killing the wither where a piglin can see.")
            .defineInRange("piglinWitherKillGain", 15.0, 0.0, 100.0);
    public static final ModConfigSpec.DoubleValue PIGLIN_GIFT_GAIN = COMMON
            .comment("Piglin standing for a gift worth one gold ingot; it grows slowly with value (a gold block about 3.3x).")
            .defineInRange("piglinGiftGain", 1.0, 0.0, 20.0);
    public static final ModConfigSpec.IntValue PIGLIN_FREEBIE_AT = COMMON
            .comment("Piglin standing at or above this: once a day a piglin will hand you something for nothing.")
            .defineInRange("piglinFreebieAt", 30, -100, 101);
    public static final ModConfigSpec.IntValue PIGLIN_HELP_AT = COMMON
            .comment("Piglin standing at or above this: piglins nearby fight whatever attacks you (101 = never).")
            .defineInRange("piglinHelpAt", 50, -100, 101);
    public static final ModConfigSpec.DoubleValue PILLAGER_HURT_LOSS = COMMON
            .comment("Pillager standing lost for hitting an illager, once a witness tells another illager.")
            .defineInRange("pillagerHurtLoss", 0.5, 0.0, 50.0);
    public static final ModConfigSpec.DoubleValue PILLAGER_CHEST_LOSS = COMMON
            .comment("Pillager standing lost for taking from an outpost's chests (allies are free to), once a witness tells.")
            .defineInRange("pillagerChestLoss", 2.0, 0.0, 50.0);
    public static final ModConfigSpec.IntValue HOUSE_LOOT_ALLOWED = COMMON
            .comment("Standing with a villager (the reputation readout scale) at which taking from their house isn't a crime,",
                    "though they mind a little. Default: Known.")
            .defineInRange("houseLootAllowedAt", 6, -100, 1000);
    public static final ModConfigSpec.IntValue HOUSE_LOOT_FREE = COMMON
            .comment("Standing with a villager at which you can take from their house and they don't mind at all. Default: Friend.")
            .defineInRange("houseLootFreeAt", 20, -100, 1000);

    public static final ModConfigSpec COMMON_SPEC = COMMON.build();

    // ---- Client ----
    private static final ModConfigSpec.Builder CLIENT = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue TEXT_SPEED = CLIENT
            .comment("Characters revealed per second in the dialogue box. 0 shows text instantly.")
            .defineInRange("textSpeed", 30, 0, 1000);

    public static final ModConfigSpec.EnumValue<VoiceStyle> VOICE_STYLE = CLIENT
            .comment("How villagers babble while their text scrolls. Experimental: try them all!")
            .defineEnum("voiceStyle", VoiceStyle.MURMUR);

    public static final ModConfigSpec.DoubleValue VOICE_VOLUME = CLIENT
            .comment("Volume of villager babble (also follows the Voice/Speech volume slider).")
            .defineInRange("voiceVolume", 0.8, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue UI_SOUNDS = CLIENT
            .comment("Play soft UI sounds when opening dialogue and choosing options.")
            .define("uiSounds", true);

    public static final ModConfigSpec.BooleanValue HIGHLIGHT_NAMES = CLIENT
            .comment("Colour the names of items and places in what people say.")
            .define("highlightNames", true);

    public static final ModConfigSpec.DoubleValue BOX_OPACITY = CLIENT
            .comment("How see-through the dialogue box and choices are (0 = invisible, 1 = solid).")
            .defineInRange("boxOpacity", 0.85, 0.15, 1.0);

    public static final ModConfigSpec.EnumValue<DialogueTheme> DIALOGUE_THEME = CLIENT
            .comment("The dialogue box's look. CLASSIC: smoked glass. TERMINAL: a green computer terminal. NORTHERN: dark",
                    "slate with silver knotwork. BLOCKY: Minecraft's own blocky panels. STORYBOOK: parchment and gold.",
                    "BRONZE: charcoal in a bronze frame. All stay see-through (boxOpacity).")
            .defineEnum("dialogueTheme", DialogueTheme.CLASSIC);

    public static final ModConfigSpec.EnumValue<DialogueFont> DIALOGUE_FONT = CLIENT
            .comment("The dialogue font. THEME: each theme's own. MINECRAFT: the pixel font. SMOOTH: the rounded font (Nunito).")
            .defineEnum("dialogueFont", DialogueFont.THEME);

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED = CLIENT
            .comment("Conversation camera: turn to face the speaker, push in, then slowly drift while you talk.")
            .define("camera", true);

    public static final ModConfigSpec.DoubleValue CAMERA_ZOOM = CLIENT
            .comment("How far the camera pushes in (fraction of your FOV).")
            .defineInRange("cameraZoom", 0.15, 0.0, 0.4);

    public static final ModConfigSpec.BooleanValue CAMERA_SWAY = CLIENT
            .comment("Gentle camera sway while a conversation lingers. Turn off if it bothers you.")
            .define("cameraSway", true);

    public static final ModConfigSpec.BooleanValue HIDE_HUD = CLIENT
            .comment("Hide the hotbar, hearts and crosshair while talking.")
            .define("hideHud", true);

    public static final ModConfigSpec.BooleanValue BACKGROUND_BLUR = CLIENT
            .comment("Blur the background behind the speaker while talking (depth of field).",
                    "Skipped automatically when an Iris shaderpack is on: enable the pack's own Depth of Field instead.")
            .define("backgroundBlur", true);

    public static final ModConfigSpec.DoubleValue BLUR_STRENGTH = CLIENT
            .comment("Strength of the background blur.")
            .defineInRange("blurStrength", 0.7, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue REPUTATION_HUD = CLIENT
            .comment("Show your standing (a small title under the crosshair) while looking at a villager or a village bell.")
            .define("reputationHud", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT.build();
}

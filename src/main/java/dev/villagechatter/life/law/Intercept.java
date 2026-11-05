package dev.villagechatter.life.law;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.SpeechCheck;
import dev.villagechatter.life.LivingVillage;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.quests.quest.Reputation;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Stopping a witness on their way to report you: right-click them as they run. Half the time (config) they snap at
 * you and keep running. Otherwise they listen, and every failure makes the remaining checks harder:
 * <ul>
 *   <li>Persuade: they keep your secret, and think a little less of you. Fail: a little less again; try something else.</li>
 *   <li>Bribe (priced by the crime): they keep quiet. Fail: as a failed persuade (and they keep their price).</li>
 *   <li>Intimidate (gear, a weapon, a bad name help): they keep quiet, but think much less of you. Fail: they flee.</li>
 *   <li>Threaten: always an exit; they flee in a panic. Kill them before anyone else sees and it never happened.</li>
 * </ul>
 * Kept quiet means no report and no spreading: they're no longer a witness (see {@link WitnessMemory}).
 */
public final class Intercept {
    private Intercept() {}

    public static final String PERSUADE = "wit_persuade", BRIBE = "wit_bribe", INTIMIDATE = "wit_intimidate", THREATEN = "wit_threaten";
    /** Opinion lost with that one villager: a kept secret, a failed try, a scared-quiet witness, a failed scare. */
    static final int PERSUADED = 2, FAILED = 3, INTIMIDATED = 10, FAILED_SCARE = 5;
    private static final int PANIC_TICKS = 600;
    private static final Map<String, Integer> VARIANTS = Map.ofEntries(Map.entry("stopped", 5), Map.entry("snap", 5),
            Map.entry("persuade_ok", 4), Map.entry("bribe_ask", 5), Map.entry("bribe_ok", 6), Map.entry("no_money", 4),
            Map.entry("intimidate_ok", 5), Map.entry("intimidate_fail", 5), Map.entry("fail", 5), Map.entry("threaten", 5));

    static final class Stop {
        final Enforcement.Report report;
        final Villager teller;
        int fails;

        Stop(Enforcement.Report report, Villager teller) {
            this.report = report;
            this.teller = teller;
        }
    }

    private static final Map<UUID, Stop> STOPS = new HashMap<>();

    /** Right-clicked a villager: if they're running to report this player, deal with it here. */
    public static boolean tryStop(ServerPlayer player, LivingEntity target) {
        if (!Law.enabled() || !(target instanceof Villager teller)) return false;
        Enforcement.Report report = Enforcement.reportBy(player.getUUID(), teller);
        if (report == null) return false;
        Stop old = STOPS.get(player.getUUID());
        if (old != null && !ConversationManager.isTalkingTo(player, old.teller)) release(player, old); // walked off, logged out
        RandomSource random = player.getRandom();
        if (STOPS.containsKey(player.getUUID()) || random.nextDouble() >= ChatterConfig.WITNESS_STOP_CHANCE.get()) {
            LivingVillage.say(player, teller, line("snap", random));
            return true;
        }
        Stop s = stop(player, report, teller);
        ConversationManager.openScripted(player, teller, options(), labels(player, s), line("stopped", random));
        return true;
    }

    static Stop stop(ServerPlayer player, Enforcement.Report report, Villager teller) {
        report.held = true;
        teller.getNavigation().stop();
        Stop s = new Stop(report, teller);
        STOPS.put(player.getUUID(), s);
        return s;
    }

    public static boolean owns(ServerPlayer player, LivingEntity speaker) {
        Stop s = STOPS.get(player.getUUID());
        return s != null && s.teller == speaker;
    }

    // ------------------------------------------------------------------ the odds

    /** The emeralds they want: scales with how bad it was. */
    static int price(CrimeLedger.Crime crime) {
        return Math.max(1, (int) Math.ceil(crime.kind.victimHit * ChatterConfig.WITNESS_BRIBE_PER_HIT.get()));
    }

    /** Petty crimes are easiest to talk away; murder is hardest. */
    static double severity(CrimeKind kind) {
        return kind.murder ? 0.35 : kind.victimHit > 10 ? 0.6 : 1.0;
    }

    /** Each failure makes the rest harder. */
    static double chained(Stop s) {
        return Math.pow(ChatterConfig.WITNESS_RETRY_FACTOR.get(), s.fails);
    }

    static double chance(ServerPlayer player, Stop s, String option) {
        CrimeKind kind = s.report.crime.kind;
        double base = switch (option) {
            case PERSUADE -> SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, ChatterConfig.WITNESS_PERSUADE_CHANCE.get(), player) * severity(kind);
            case BRIBE -> ChatterConfig.WITNESS_BRIBE_CHANCE.get() * Math.max(0.5, severity(kind));
            case INTIMIDATE -> intimidation(player, s.teller, kind);
            default -> 1;
        };
        return Math.min(1, base) * chained(s);
    }

    /** Heavy armor and blood (charisma's menace), a weapon in hand and a bad reputation make you scarier. */
    static double intimidation(ServerPlayer player, Villager teller, CrimeKind kind) {
        double chance = SpeechCheck.adjust(SpeechCheck.Skill.INTIMIDATE, ChatterConfig.WITNESS_INTIMIDATE_CHANCE.get(), player);
        if (dev.villagechatter.reputation.faction.FactionBook.armed(player.getMainHandItem())) chance += 0.1;
        if (dev.villagechatter.reputation.Standing.score(player, teller) < -20) chance += 0.1;
        return chance * severity(kind);
    }

    // ------------------------------------------------------------------ the talk

    public static void onAction(ServerPlayer player, LivingEntity speaker, String action) {
        Stop s = STOPS.get(player.getUUID());
        if (s == null) return;
        boolean pass = dev.villagechatter.life.Speech.roll(player, chance(player, s, action));
        switch (action) {
            case PERSUADE -> {
                if (pass) keepQuiet(player, s, PERSUADED, "persuade_ok");
                else failed(player, s);
            }
            case BRIBE -> {
                int cost = price(s.report.crime);
                if (emeralds(player) < cost) {
                    reply(player, s, "no_money", options(), cost);
                } else if (pass) {
                    takeEmeralds(player, cost);
                    keepQuiet(player, s, 0, "bribe_ok");
                } else {
                    failed(player, s);
                }
            }
            case INTIMIDATE -> {
                if (pass) keepQuiet(player, s, INTIMIDATED, "intimidate_ok");
                else {
                    hurt(player, s.teller, FAILED_SCARE);
                    flee(player, s, "intimidate_fail");
                }
            }
            case THREATEN -> threaten(player, s);
            case DialogueAction.GOODBYE -> release(player, s);
            default -> { }
        }
    }

    /** A failed persuade or bribe: they think a little less of you, the rest gets harder, and you can try again. */
    static void failed(ServerPlayer player, Stop s) {
        s.fails++;
        hurt(player, s.teller, FAILED);
        reply(player, s, "fail", options());
    }

    /** They won't report it, and it goes no further than them. */
    static void keepQuiet(ServerPlayer player, Stop s, int opinionLoss, String line) {
        hurt(player, s.teller, opinionLoss);
        CrimeLedger.Crime crime = s.report.crime;
        crime.witnesses.remove(s.teller.getUUID());
        if (crime.witnesses.isEmpty() && !crime.reported) {
            CrimeLedger ledger = CrimeLedger.get(player.server);
            ledger.standing(player.getUUID(), crime.village).crimes.remove(crime);
            ledger.setDirty();
        }
        Enforcement.silenced(s.report);
        STOPS.remove(player.getUUID());
        reply(player, s, line, List.of(DialogueAction.GOODBYE));
    }

    /** "You'll never leave here alive": they run for it, faster, and the threat is a crime too. */
    static void threaten(ServerPlayer player, Stop s) {
        flee(player, s, "threaten");
        Law.commit(player, CrimeKind.THREAT, s.teller, null, null);
    }

    /** The talk is over: they say their piece and run on to report you, faster. */
    static void flee(ServerPlayer player, Stop s, String line) {
        ConversationManager.endScripted(player);
        LivingVillage.say(player, s.teller, line(line, player.getRandom()));
        s.teller.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, PANIC_TICKS, 1));
        release(player, s);
    }

    /** They go on their way to report you. */
    static void release(ServerPlayer player, Stop s) {
        s.report.held = false;
        STOPS.remove(player.getUUID());
    }

    private static void hurt(ServerPlayer player, Villager teller, int amount) {
        if (amount > 0) Reputation.adjust(player, teller, -amount, false);
    }

    private static List<String> options() {
        return List.of(PERSUADE, BRIBE, INTIMIDATE, THREATEN, DialogueAction.GOODBYE);
    }

    /** Speech-check tags with the odds as they stand now. */
    static Map<String, Component> labels(ServerPlayer player, Stop s) {
        Map<String, Component> labels = new LinkedHashMap<>();
        labels.put(PERSUADE, SpeechCheck.label(SpeechCheck.Skill.PERSUADE, chance(player, s, PERSUADE),
                Component.translatable("villagechatter.option." + PERSUADE)));
        labels.put(BRIBE, SpeechCheck.label(SpeechCheck.Skill.BRIBE, chance(player, s, BRIBE),
                Component.translatable("villagechatter.option.wit_bribe.cost", price(s.report.crime))));
        labels.put(INTIMIDATE, SpeechCheck.label(SpeechCheck.Skill.INTIMIDATE, chance(player, s, INTIMIDATE),
                Component.translatable("villagechatter.option." + INTIMIDATE)));
        return labels;
    }

    private static void reply(ServerPlayer player, Stop s, String kind, List<String> options, Object... args) {
        Component said = line(kind, player.getRandom(), args);
        // "Make it worth my while": the ask comes first.
        if (kind.equals("bribe_ok") || kind.equals("no_money")) said = line("bribe_ask", player.getRandom()).copy().append(" ").append(said);
        ConversationManager.scriptedLine(player, s.teller, options, labels(player, s), said);
    }

    static Component line(String kind, RandomSource random, Object... args) {
        return Component.translatable("villagechatter.witness." + kind + "." + random.nextInt(VARIANTS.get(kind)), args);
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
}

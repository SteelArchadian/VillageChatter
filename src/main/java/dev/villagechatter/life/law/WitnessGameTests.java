package dev.villagechatter.life.law;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Crimes;
import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Tier;
import dev.villagechatter.api.SpeechCheck;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.reputation.faction.Allegiance;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Witnesses: each crime's day clock, and stopping one on their way to report you. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WitnessGameTests {
    private WitnessGameTests() {}

    private static Villager villager(GameTestHelper helper, int x, int z) {
        Villager v = helper.spawn(EntityType.VILLAGER, new BlockPos(x, 1, z));
        v.setNoAi(true);
        return v;
    }

    /** Not creative (the law ignores creative players, and mock players count as creative), and packets go nowhere. */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "witnessed"));
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    private static long now(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld().getGameTime();
    }

    private static GlobalPos village(GameTestHelper helper) {
        return GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(0, 1, 0)));
    }

    /** A crime this player committed some ticks ago, seen by these witnesses. */
    private static CrimeLedger.Crime crime(GameTestHelper helper, ServerPlayer player, CrimeKind kind, long ago, Villager... seen) {
        CrimeLedger.Crime crime = new CrimeLedger.Crime(kind, village(helper), null, now(helper) - ago);
        for (Villager v : seen) crime.witnesses.put(v.getUUID(), 2);
        CrimeLedger.get(player.server).standing(player.getUUID(), crime.village).crimes.add(crime);
        return crime;
    }

    private static boolean onRecord(ServerPlayer player, CrimeLedger.Crime crime) {
        return CrimeLedger.get(player.server).standing(player.getUUID(), crime.village).crimes.contains(crime);
    }

    // ------------------------------------------------------------------ the day clock

    /** Every witness dead inside the day: the crime is gone, and the faction never heard a thing. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void wipeWithinDay(GameTestHelper helper) {
        var player = player(helper);
        Villager a = villager(helper, 1, 1), b = villager(helper, 3, 1);
        var crime = crime(helper, player, CrimeKind.KILL_VILLAGER, 100, a, b);
        helper.assertTrue(WitnessMemory.hasPending(player.server, player.getUUID(), crime.village), "inside the day: the news stays home");
        double before = Factions.village(player);
        WitnessMemory.forget(helper.getLevel(), a.getUUID());
        helper.assertTrue(onRecord(player, crime), "one witness left: it stands");
        WitnessMemory.forget(helper.getLevel(), b.getUUID());
        helper.assertFalse(onRecord(player, crime), "all of them: gone");
        helper.assertFalse(WitnessMemory.hasPending(player.server, player.getUUID(), crime.village), "nothing left to spread");
        helper.assertTrue(Factions.village(player) == before, "no faction change");
        helper.succeed();
    }

    /** A witness lived out the day: locked in, and killing them now changes nothing. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void survivorLocksIt(GameTestHelper helper) {
        var player = player(helper);
        Villager a = villager(helper, 1, 1);
        var crime = crime(helper, player, CrimeKind.THEFT, ChatterConfig.WITNESS_MEMORY_TICKS.get() + 1, a);
        helper.assertFalse(WitnessMemory.hasPending(player.server, player.getUUID(), crime.village), "the day is out");
        WitnessMemory.forget(helper.getLevel(), a.getUUID());
        helper.assertTrue(onRecord(player, crime), "locked in");
        helper.succeed();
    }

    /** Reported from afar (a caravan): nothing until the deadline, then the village and the faction. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void reportLater(GameTestHelper helper) {
        var player = player(helper);
        Villager w = villager(helper, 1, 1);
        GlobalPos village = village(helper);
        helper.assertFalse(Crimes.reportLater(player, CrimeKind.THEFT, village, List.of()), "nobody saw it");
        helper.assertTrue(Crimes.reportLater(player, CrimeKind.THEFT, village, List.of(w.getUUID())), "reported");
        var crime = CrimeLedger.get(player.server).standing(player.getUUID(), village).crimes.get(0);
        helper.assertTrue(crime.deferred && !crime.reported, "on its way");
        double before = Factions.village(player);
        WitnessMemory.land(player, player.server, village, crime);
        helper.assertTrue(crime.reported && !crime.deferred, "arrived: the guards know");
        helper.assertTrue(Factions.village(player) < before, "and word spreads");
        helper.assertTrue(VillageStanding.get(helper.getLevel()).get(village.pos(), player.getUUID(), VillageStanding.Group.ADVOCACY) < 0,
                "the village thinks less of you");
        helper.succeed();
    }

    // ------------------------------------------------------------------ stopping a witness

    private record Setup(ServerPlayer player, Villager teller, CrimeLedger.Crime crime, Enforcement.Report report, Intercept.Stop stop) {}

    private static Setup stopped(GameTestHelper helper) {
        var player = player(helper);
        Villager teller = villager(helper, 1, 1);
        var crime = crime(helper, player, CrimeKind.THEFT, 10, teller);
        var report = Enforcement.startReport(player, teller, crime, null);
        return new Setup(player, teller, crime, report, Intercept.stop(player, report, teller));
    }

    private static void withChances(double persuade, double bribe, double intimidate, Runnable test) {
        double p = ChatterConfig.WITNESS_PERSUADE_CHANCE.get(), b = ChatterConfig.WITNESS_BRIBE_CHANCE.get(),
                i = ChatterConfig.WITNESS_INTIMIDATE_CHANCE.get();
        try {
            ChatterConfig.WITNESS_PERSUADE_CHANCE.set(persuade);
            ChatterConfig.WITNESS_BRIBE_CHANCE.set(bribe);
            ChatterConfig.WITNESS_INTIMIDATE_CHANCE.set(intimidate);
            test.run();
        } finally {
            ChatterConfig.WITNESS_PERSUADE_CHANCE.set(p);
            ChatterConfig.WITNESS_BRIBE_CHANCE.set(b);
            ChatterConfig.WITNESS_INTIMIDATE_CHANCE.set(i);
        }
    }

    /** Persuade: a failure costs a little and makes everything after it harder; a pass keeps the secret for a little. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void witnessPersuade(GameTestHelper helper) {
        var s = stopped(helper);
        withChances(0, 0, 0, () -> Intercept.onAction(s.player(), s.teller(), Intercept.PERSUADE));
        helper.assertTrue(s.stop().fails == 1 && s.report().held, "failed, still talking");
        helper.assertTrue(s.teller().getPlayerReputation(s.player()) == -Intercept.FAILED, "a little less");
        double chained = Intercept.chance(s.player(), s.stop(), Intercept.BRIBE);
        helper.assertTrue(chained < ChatterConfig.WITNESS_BRIBE_CHANCE.get(), "the rest got harder");
        withChances(1.0, 0, 0, () -> {
            s.stop().fails = 0; // a sure thing, for the test
            Intercept.onAction(s.player(), s.teller(), Intercept.PERSUADE);
        });
        helper.assertTrue(Enforcement.reportBy(s.player().getUUID(), s.teller()) == null, "no report");
        helper.assertFalse(onRecord(s.player(), s.crime()), "it goes no further");
        helper.assertTrue(s.teller().getPlayerReputation(s.player()) == -Intercept.FAILED - Intercept.PERSUADED, "a small price with them");
        helper.succeed();
    }

    /** Bribe: no money, no deal; paid and taken, they keep quiet. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void witnessBribe(GameTestHelper helper) {
        var s = stopped(helper);
        withChances(0, 1.0, 0, () -> Intercept.onAction(s.player(), s.teller(), Intercept.BRIBE));
        helper.assertTrue(s.report().held && s.stop().fails == 0, "no money: nothing happens");
        int price = Intercept.price(s.crime());
        s.player().getInventory().add(new ItemStack(Items.EMERALD, price));
        withChances(0, 1.0, 0, () -> Intercept.onAction(s.player(), s.teller(), Intercept.BRIBE));
        helper.assertTrue(Enforcement.reportBy(s.player().getUUID(), s.teller()) == null && !onRecord(s.player(), s.crime()), "bought silence");
        helper.assertTrue(s.player().getInventory().countItem(Items.EMERALD) == 0, "paid");
        helper.succeed();
    }

    /** Intimidate: a pass costs a lot with them; a fail costs some and they run. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void witnessIntimidate(GameTestHelper helper) {
        var fail = stopped(helper);
        withChances(0, 0, 0, () -> Intercept.onAction(fail.player(), fail.teller(), Intercept.INTIMIDATE));
        helper.assertTrue(!fail.report().held && fail.teller().hasEffect(MobEffects.MOVEMENT_SPEED), "they flee");
        helper.assertTrue(fail.teller().getPlayerReputation(fail.player()) == -Intercept.FAILED_SCARE, "some loss");
        var pass = stopped(helper);
        withChances(0, 0, 1.0, () -> Intercept.onAction(pass.player(), pass.teller(), Intercept.INTIMIDATE));
        helper.assertTrue(Enforcement.reportBy(pass.player().getUUID(), pass.teller()) == null, "scared quiet");
        helper.assertTrue(pass.teller().getPlayerReputation(pass.player()) == -Intercept.INTIMIDATED, "a big loss");
        helper.succeed();
    }

    /** Threaten: always an exit. They flee in a panic, and the threat is a crime too. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void witnessThreaten(GameTestHelper helper) {
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.BELL); // it happened in a village
        helper.runAfterDelay(3, () -> {
            var s = stopped(helper);
            Intercept.onAction(s.player(), s.teller(), Intercept.THREATEN);
            helper.assertTrue(!s.report().held && s.teller().hasEffect(MobEffects.MOVEMENT_SPEED), "they run for it");
            helper.assertTrue(CrimeLedger.get(s.player().server).all(s.player().getUUID()).values().stream()
                    .anyMatch(st -> st.crimes.stream().anyMatch(c -> c.kind == CrimeKind.THREAT)), "the threat is on record");
            helper.succeed();
        });
    }

    /** Speech checks: yellow easy, orange medium, red hard. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void speechCheckColors(GameTestHelper helper) {
        helper.assertTrue(SpeechCheck.color(0.8) == SpeechCheck.EASY && SpeechCheck.color(0.4) == SpeechCheck.MEDIUM
                && SpeechCheck.color(0.1) == SpeechCheck.HARD, "colors by difficulty");
        helper.assertTrue(SpeechCheck.label(SpeechCheck.Skill.PERSUADE, 0.5, net.minecraft.network.chat.Component.literal("x"))
                .getString().startsWith("["), "tagged");
        helper.succeed();
    }

    /** Both meters count: villagers see a pillager friend differently, pillagers a village hero. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bothFactions(GameTestHelper helper) {
        helper.assertTrue("both".equals(Allegiance.villagePool(30, Tier.TOLERATED)), "friend to both");
        helper.assertTrue("pillager_friend".equals(Allegiance.villagePool(0, Tier.ALLY)), "only theirs");
        helper.assertTrue(Allegiance.villagePool(80, Tier.HATED) == null, "just ours");
        helper.assertTrue("hero_friend".equals(Allegiance.pillagerPool(true, Tier.ALLY)), "hero and ally");
        helper.assertTrue("hero_enemy".equals(Allegiance.pillagerPool(true, Tier.WARY)), "hero they hate");
        helper.assertTrue("enemy".equals(Allegiance.pillagerPool(false, Tier.HATED)), "enemy");
        helper.assertTrue(ChatterAttachments.FACTIONS != null && FactionBook.enabled(), "factions on");
        helper.succeed();
    }
}

package dev.villagechatter.reputation.faction;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.api.Factions;
import dev.villagechatter.life.law.CrimeKind;
import dev.villagechatter.life.law.CrimeLedger;
import dev.villagechatter.life.law.VillageStanding;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Pillager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Faction standings: the two meters, how villages add up, word on the road, crimes and hearsay. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class FactionGameTests {
    private FactionGameTests() {}

    private static final long DAY = 24000;

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /** Pillagers start out hating you; the tiers follow the meter, which stops at 100. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pillagerTiers(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(Factions.pillager(player) == -50 && Factions.tier(player) == Factions.Tier.HATED, "hated at the start");
        Factions.adjust(player, Factions.Faction.PILLAGER, 15);
        helper.assertTrue(Factions.tier(player) == Factions.Tier.WARY, "wary at -35");
        Factions.adjust(player, Factions.Faction.PILLAGER, 40);
        helper.assertTrue(Factions.tier(player) == Factions.Tier.TOLERATED, "tolerated at 5");
        Factions.adjust(player, Factions.Faction.PILLAGER, 500);
        helper.assertTrue(Factions.tier(player) == Factions.Tier.ALLY && Factions.pillager(player) == 100, "ally, capped at 100");
        helper.succeed();
    }

    /** Big, recent villages count for more than small ones you saw long ago. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void villageSummary(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        long now = now(player);
        var book = player.getData(ChatterAttachments.FACTIONS);
        book.villages.put("a", new FactionBook.Seen(40, 12, now, now));
        book.villages.put("b", new FactionBook.Seen(-40, 2, now - 90 * DAY, now - 90 * DAY));
        player.setData(ChatterAttachments.FACTIONS, book);
        double v = Factions.village(player);
        helper.assertTrue(v > 35 && v < 40, "mostly the big recent village, not " + v);
        helper.succeed();
    }

    /** Word on the road fades toward nothing. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void roamingFades(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        Factions.adjust(player, Factions.Faction.VILLAGE, -20);
        helper.assertTrue(Math.abs(Factions.village(player) + 20) < 0.01, "-20 right away");
        var book = player.getData(ChatterAttachments.FACTIONS);
        book.roamingAt -= 7 * DAY; // the default half-life
        helper.assertTrue(Math.abs(Factions.village(player) + 10) < 0.1, "half that a week later");
        helper.succeed();
    }

    /** More witnesses, bigger hit; standing cushions petty crimes and makes murder worse. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void crimeScaling(GameTestHelper helper) {
        helper.assertTrue(FactionBook.crimeScale(CrimeKind.THEFT, 5, 0) > FactionBook.crimeScale(CrimeKind.THEFT, 1, 0), "witnesses count");
        helper.assertTrue(FactionBook.crimeScale(CrimeKind.THEFT, 1, 80) < FactionBook.crimeScale(CrimeKind.THEFT, 1, 0), "petty: cushioned");
        helper.assertTrue(FactionBook.crimeScale(CrimeKind.KILL_VILLAGER, 1, 80) > FactionBook.crimeScale(CrimeKind.KILL_VILLAGER, 1, 0),
                "murder: the higher you stood, the harder you fall");
        helper.assertTrue(FactionBook.crimeScale(CrimeKind.THEFT, 50, 0) <= 2, "never more than double for a crowd");
        helper.succeed();
    }

    /** A village you've never visited starts at a share of your village standing. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void newVillageInherits(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        Factions.adjust(player, Factions.Faction.VILLAGE, 40);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        FactionBook.firstVisit(player, helper.getLevel(), anchor);
        double head = VillageStanding.get(helper.getLevel()).get(anchor, player.getUUID(), VillageStanding.Group.ADVOCACY);
        helper.assertTrue(Math.abs(head - 20) < 0.1, "half of 40, not " + head);
        helper.succeed();
    }

    /** Killing raiders: pillagers like you less, villages more. Tolerated: pillagers leave you be until you hit one. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pillagerTruce(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        FactionBook.crossover(player, -2);
        helper.assertTrue(Factions.pillager(player) == -52 && Factions.village(player) > 0.9, "both meters move");
        // Not added to the world: a real pillager nearby would upset other tests (lost villagers, raids).
        Pillager pillager = EntityType.PILLAGER.create(helper.getLevel());
        helper.assertFalse(FactionBook.truce(player, pillager), "hated: no truce");
        Factions.adjust(player, Factions.Faction.PILLAGER, 60);
        helper.assertTrue(FactionBook.truce(player, pillager), "tolerated: they let you pass");
        FactionBook.grudge(player); // what hitting one does
        helper.assertFalse(FactionBook.truce(player, pillager), "until you start something");
        helper.succeed();
    }

    /** Crimes that reached a village before you did are what they've heard; later ones aren't hearsay. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theyHeardAboutYou(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        GlobalPos village = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(3, 1, 3)));
        long now = now(player);
        var ledger = CrimeLedger.get(player.server);
        var fresh = new CrimeLedger.Crime(CrimeKind.THEFT, village, null, now - 100);
        fresh.witnesses.put(java.util.UUID.randomUUID(), 0);
        ledger.standing(player.getUUID(), village).crimes.add(fresh);
        helper.assertTrue(Hearsay.heard(player, village).isEmpty(), "inside its day the news hasn't spread");
        long day = dev.villagechatter.ChatterConfig.WITNESS_MEMORY_TICKS.get();
        ledger.standing(player.getUUID(), village).crimes.add(new CrimeLedger.Crime(CrimeKind.THEFT, village, null, now - day - 100));
        helper.assertTrue(Hearsay.heard(player, village).isPresent(), "never been there: they've heard");
        var book = player.getData(ChatterAttachments.FACTIONS);
        book.villages.put(FactionBook.key(village), new FactionBook.Seen(0, 5, now - 200, now - day - 200));
        helper.assertTrue(Hearsay.heard(player, village).isEmpty(), "been there first: it isn't hearsay");
        helper.succeed();
    }
}

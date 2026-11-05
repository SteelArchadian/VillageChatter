package dev.villagechatter.life.job;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.reputation.Standing;
import dev.villagechatter.reputation.Upbringing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Kids growing up, newborns' first opinions, and "About your job". */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class JobGameTests {
    private JobGameTests() {}

    private static Villager villager(GameTestHelper helper, int x, int z) {
        Villager v = helper.spawn(EntityType.VILLAGER, new BlockPos(x, 1, z));
        v.setNoAi(true); // no brain: it mustn't claim workstations on its own mid-test
        return v;
    }

    /** Growing up is the same villager: gossip and goodwill both stay. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void kidKeepsStanding(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        Villager kid = villager(helper, 2, 2);
        kid.setAge(-24000);
        kid.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, 25);
        player.getData(QuestRegistries.GOODWILL).points.put(kid.getUUID(), 3.0);
        double before = Standing.score(player, kid);
        helper.assertTrue(kid.isBaby() && before >= 27.9, "the kid likes you");
        kid.setAge(0); // ageUp
        helper.assertFalse(kid.isBaby(), "grown up");
        helper.assertTrue(Math.abs(Standing.score(player, kid) - before) < 0.001, "and still likes you just as much");
        helper.succeed();
    }

    /** A newborn starts at its parents' average opinion. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void newbornInherits(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        Villager mom = villager(helper, 1, 1), dad = villager(helper, 2, 1), baby = villager(helper, 1, 2);
        baby.setAge(-24000);
        mom.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, 20);
        dad.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, 10);
        player.getData(QuestRegistries.GOODWILL).points.remove(baby.getUUID());
        Upbringing.inherit(player, helper.getLevel(), baby, mom, dad);
        helper.assertTrue(Math.abs(Standing.score(player, baby) - 15.0) < 0.01, "the average of 20 and 10");
        helper.succeed();
    }

    /** Assigning takes the workstation, sets the job and its memory, and is remembered. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void assignJob(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 1, 1);
        helper.setBlock(rel, Blocks.LECTERN);
        Villager v = villager(helper, 1, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.runAfterDelay(3, () -> { // the POI is registered on the next server task
            var result = JobAssign.assign(v, VillagerProfession.LIBRARIAN, pos);
            helper.assertTrue(result.outcome() == JobAssign.Outcome.ASSIGNED, "assigned, not " + result.outcome());
            helper.assertTrue(v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN, "a librarian now");
            helper.assertTrue(v.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).filter(pos::equals).isPresent(), "working at the lectern");
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(pos) == 0, "the lectern is claimed");
            helper.assertTrue("minecraft:librarian".equals(v.getData(ChatterAttachments.ASSIGNED_JOB)), "and the job is remembered");
            var wrong = JobAssign.assign(v, VillagerProfession.FARMER, pos);
            helper.assertTrue(wrong.outcome() == JobAssign.Outcome.WRONG_BLOCK, "a lectern is no farm");
            helper.succeed();
        });
    }

    /**
     * Taking a claimed workstation leaves its holder jobless: no profession, no trades (even a veteran's), and no
     * claiming a free block on their own until a player assigns them.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void orphanedJobless(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 4), Blocks.LECTERN);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.LECTERN);
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 4)), spare = helper.absolutePos(new BlockPos(4, 1, 4));
        Villager a = villager(helper, 1, 1), b = villager(helper, 4, 1);
        helper.runAfterDelay(3, () -> {
            JobAssign.assign(b, VillagerProfession.LIBRARIAN, first);
            b.setVillagerXp(60);
            b.setVillagerData(b.getVillagerData().setLevel(2));
            var result = JobAssign.assign(a, VillagerProfession.LIBRARIAN, first);
            helper.assertTrue(result.outcome() == JobAssign.Outcome.BUMPED && result.previousOwner() == b, "b was bumped");
            helper.assertTrue(a.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).filter(first::equals).isPresent(), "a works at the lectern");
            helper.assertTrue(b.getVillagerData().getProfession() == VillagerProfession.NONE && b.getVillagerXp() == 0
                    && b.getVillagerData().getLevel() == 1 && b.getOffers().isEmpty(), "b is jobless, trades and all");
            helper.assertTrue(b.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty() && b.getData(ChatterAttachments.AWAITING_JOB), "and waiting");
            var poi = helper.getLevel().getPoiManager();
            // Vanilla's jobless villager claims a free block: one awaiting assignment lets it go again.
            poi.take(h -> true, (h, p) -> p.equals(spare), spare, 1);
            b.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), spare));
            JobAssign.holdOffClaims(b);
            helper.assertTrue(b.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).isEmpty() && poi.getFreeTickets(spare) == 1, "the spare lectern stays free");
            JobAssign.assign(b, VillagerProfession.LIBRARIAN, spare);
            helper.assertTrue(!b.getData(ChatterAttachments.AWAITING_JOB) && b.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN, "until a player gives them a job");
            helper.succeed();
        });
    }

    /** A villager no player touched keeps the claim vanilla gave it; one awaiting assignment lets go on its next tick. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void untouchedStaysVanilla(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 4), Blocks.LECTERN);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.LECTERN);
        BlockPos mine = helper.absolutePos(new BlockPos(1, 1, 4)), theirs = helper.absolutePos(new BlockPos(4, 1, 4));
        Villager untouched = villager(helper, 1, 1), waiting = villager(helper, 4, 1);
        waiting.setData(ChatterAttachments.AWAITING_JOB, true);
        var poi = helper.getLevel().getPoiManager();
        helper.runAfterDelay(3, () -> { // what vanilla's AcquirePoi does when a jobless villager spots a free block
            poi.take(h -> true, (h, p) -> p.equals(mine), mine, 1);
            untouched.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), mine));
            poi.take(h -> true, (h, p) -> p.equals(theirs), theirs, 1);
            waiting.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), theirs));
        });
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(untouched.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).isPresent() && poi.getFreeTickets(mine) == 0, "the untouched villager keeps its claim");
            helper.assertTrue(untouched.getData(ChatterAttachments.ASSIGNED_JOB).isEmpty() && !untouched.getData(ChatterAttachments.AWAITING_JOB), "and nothing of ours is on it");
            helper.assertTrue(waiting.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).isEmpty() && poi.getFreeTickets(theirs) == 1, "the waiting one lets go");
            helper.succeed();
        });
    }

    /** Vanilla makes them jobless when the block goes; an assigned job comes back. Someone else's change wins. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void keepAssignedJob(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.LECTERN);
        Villager v = villager(helper, 1, 1);
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.runAfterDelay(3, () -> {
            JobAssign.assign(v, VillagerProfession.LIBRARIAN, pos);
            helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR);
            v.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.NONE)); // what vanilla's reset does
            JobAssign.keep(v);
            helper.assertTrue(v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN, "still a librarian");
            v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.CLERIC));
            JobAssign.keep(v);
            helper.assertTrue(v.getVillagerData().getProfession() == VillagerProfession.CLERIC
                    && v.getData(ChatterAttachments.ASSIGNED_JOB).isEmpty(), "a job given some other way sticks");
            helper.succeed();
        });
    }

    /** Kids and nitwits can't work; traded villagers start over, or keep their job if the server says so. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void whoCanWork(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.LECTERN);
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 1));
        Villager kid = villager(helper, 1, 1), nitwit = villager(helper, 1, 3), veteran = villager(helper, 1, 5);
        kid.setAge(-24000);
        nitwit.setVillagerData(nitwit.getVillagerData().setProfession(VillagerProfession.NITWIT));
        veteran.setVillagerData(veteran.getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(3));
        veteran.setVillagerXp(80);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(JobAssign.assign(kid, VillagerProfession.LIBRARIAN, pos).outcome() == JobAssign.Outcome.CANT_WORK, "kids can't");
            helper.assertTrue(JobAssign.assign(nitwit, VillagerProfession.LIBRARIAN, pos).outcome() == JobAssign.Outcome.CANT_WORK, "nitwits can't");
            boolean was = ChatterConfig.RETRAIN_TRADED.get();
            try {
                ChatterConfig.RETRAIN_TRADED.set(false);
                helper.assertTrue(JobAssign.assign(veteran, VillagerProfession.LIBRARIAN, pos).outcome() == JobAssign.Outcome.LOCKED, "locked when the server says so");
                ChatterConfig.RETRAIN_TRADED.set(true);
                helper.assertTrue(JobAssign.assign(veteran, VillagerProfession.LIBRARIAN, pos).outcome() == JobAssign.Outcome.ASSIGNED, "otherwise retrained");
            } finally {
                ChatterConfig.RETRAIN_TRADED.set(was);
            }
            helper.assertTrue(veteran.getVillagerXp() == 0 && veteran.getVillagerData().getLevel() == 1, "starting over as a novice");
            helper.succeed();
        });
    }
}

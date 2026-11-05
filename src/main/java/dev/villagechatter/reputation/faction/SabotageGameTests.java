package dev.villagechatter.reputation.faction;

import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The three-way feud (Sabotage): kills please the rivals; jobs are offered, turned down or done and reported. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class SabotageGameTests {
    private SabotageGameTests() {}

    /** A survival fake player (the mock one is creative, and can't be sent dialogue). */
    private static net.minecraft.server.level.ServerPlayer player(GameTestHelper helper) {
        var p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "saboteur"));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** A villager's job: once a day; doing it and reporting back earns standing, turning one down costs a little. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void sabotageJob(GameTestHelper helper) {
        var player = player(helper);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setNoAi(true);
        helper.assertTrue(Sabotage.options(player, Faction.VILLAGE).contains(Sabotage.ASK), "a villager has work");
        Sabotage.handle(player, villager, Sabotage.ASK);
        var job = Sabotage.job(player).orElseThrow();
        helper.assertTrue(job.issuer() == Faction.VILLAGE && job.target() != Faction.VILLAGE, "a job against a rival");
        helper.assertTrue(Sabotage.options(player, Faction.VILLAGE).isEmpty(), "one offer at a time");
        Sabotage.handle(player, villager, Sabotage.ACCEPT);
        for (int i = 0; i < job.count(); i++) Sabotage.progress(player, job.target(), job.kind());
        helper.assertTrue(Sabotage.options(player, Faction.VILLAGE).contains(Sabotage.DONE), "report back when done");
        double before = Factions.village(player);
        Sabotage.handle(player, villager, Sabotage.DONE);
        helper.assertTrue(Factions.village(player) > before + 5, "the village rewards a job done");
        helper.assertTrue(Sabotage.options(player, Faction.VILLAGE).isEmpty(), "no second offer the same day");

        double piglins = Factions.piglin(player);
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(3, 1, 3));
        piglin.setNoAi(true);
        Sabotage.handle(player, piglin, Sabotage.ASK);
        helper.assertTrue(Sabotage.job(player).isPresent(), "piglins have work too");
        Sabotage.handle(player, piglin, Sabotage.DECLINE);
        helper.assertTrue(Factions.piglin(player) < piglins && Sabotage.job(player).isEmpty(), "turning it down costs a little");
        helper.succeed();
    }

    /** Killing a piglin pleases villages and pillagers a little. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void feudKills(GameTestHelper helper) {
        var player = player(helper);
        double pillagers = Factions.pillager(player), village = Factions.village(player);
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(2, 1, 2));
        piglin.setNoAi(true);
        piglin.hurt(player.damageSources().playerAttack(player), 1000);
        helper.assertTrue(Factions.pillager(player) > pillagers && Factions.village(player) > village, "the rivals approve");
        helper.succeed();
    }

    /** Only captains talk business; a raid kill a pillager sees pleases them and costs the villages dearly. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void captainsAndRaids(GameTestHelper helper) {
        var player = player(helper);
        Factions.adjust(player, Faction.PILLAGER, 80);
        var grunt = helper.spawn(EntityType.PILLAGER, new BlockPos(1, 1, 3));
        grunt.setNoAi(true);
        var captain = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        captain.setNoAi(true);
        captain.setPatrolLeader(true);
        helper.assertTrue(PillagerTalk.canTalk(player, grunt), "tolerated: they'll talk");
        helper.assertTrue(PillagerTalk.options(player, grunt).equals(java.util.List.of(dev.villagechatter.server.ConversationManager.GIFT)),
                "a grunt says a line and takes gifts, nothing more");
        helper.assertTrue(PillagerTalk.options(player, captain).contains(Sabotage.ASK), "the captain has work");

        long now = player.server.overworld().getGameTime();
        BlockPos village = helper.absolutePos(new BlockPos(2, 1, 2));
        Sabotage.save(player, java.util.Optional.of(new Sabotage.Job(Faction.PILLAGER, Faction.VILLAGE, Sabotage.Kind.RAID, 3, 0,
                now + 24000, true, village.asLong())), java.util.Map.of());
        double pillagers = Factions.pillager(player), villages = Factions.village(player);
        var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 1));
        villager.setNoAi(true);
        villager.hurt(player.damageSources().playerAttack(player), 1000);
        helper.assertTrue(Sabotage.job(player).orElseThrow().progress() == 1, "a raid kill they saw counts");
        helper.assertTrue(Factions.pillager(player) > pillagers + 2 && Factions.village(player) < villages - 5, "they cheer; the villages won't forget");
        helper.succeed();
    }

    /**
     * A village wiped out by your hand: the villages hold it against you, and villagers nearby lose heart. (The "last
     * one, your share" detection needs a village of its own; parallel tests put other villagers too close for that.)
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void wipeoutCosts(GameTestHelper helper) {
        var player = player(helper);
        var neighbour = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        neighbour.setNoAi(true);
        double before = Factions.village(player);
        Wipeout.wiped(player, helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.assertTrue(Factions.village(player) < before - 25, "the villages know");
        helper.assertTrue(neighbour.getGossips().getReputation(player.getUUID(), t -> true) < 0, "neighbours lose heart");
        helper.succeed();
    }

    /** Under their banner (placeholder flag) pillagers see an Uneasy stranger; a Hero of the Village is their enemy. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bannerAndHero(GameTestHelper helper) {
        var player = player(helper);
        Factions.adjust(player, Faction.PILLAGER, 120);
        var pillager = net.minecraft.world.entity.EntityType.PILLAGER.create(helper.getLevel());
        var banner = net.minecraft.world.entity.raid.Raid.getLeaderBannerInstance(
                helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, banner);
        helper.assertTrue(Factions.tier(player) == Factions.Tier.ALLY, "the banner never lowers what they think of you");
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
        helper.assertTrue(Factions.tier(player) == Factions.Tier.ALLY, "take it off and they know you");
        helper.assertTrue(FactionBook.truce(player, pillager), "allies have a truce");
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE, 200));
        helper.assertFalse(FactionBook.truce(player, pillager), "a Hero of the Village is their enemy");
        helper.succeed();
    }

    /** Piglins: hand over the gold you were asked for; the heirloom hides only in containers made with the world. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void piglinQuests(GameTestHelper helper) {
        var player = player(helper);
        var piglin = helper.spawn(EntityType.PIGLIN, new BlockPos(3, 1, 3));
        piglin.setNoAi(true);
        long now = player.server.overworld().getGameTime();
        Sabotage.save(player, java.util.Optional.of(new Sabotage.Job(Faction.PIGLIN, Faction.PIGLIN, Sabotage.Kind.GOLD, 4, 0, now + 24000, true, 0)),
                java.util.Map.of());
        helper.assertFalse(Sabotage.options(player, Faction.PIGLIN).contains(Sabotage.DONE), "no gold, nothing to report");
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 5));
        helper.assertTrue(Sabotage.options(player, Faction.PIGLIN).contains(Sabotage.DONE), "gold in hand: report");
        double before = Factions.piglin(player);
        Sabotage.handle(player, piglin, Sabotage.DONE);
        helper.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.GOLD_INGOT) == 1, "they take what they asked");
        helper.assertTrue(Factions.piglin(player) > before, "and like you for it");

        BlockPos placedAt = new BlockPos(1, 1, 4), worldAt = new BlockPos(4, 1, 1);
        helper.setBlock(placedAt, net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(worldAt, net.minecraft.world.level.block.Blocks.BARREL);
        dev.villagechatter.life.Misdeeds.PlacedContainers.get(helper.getLevel()).remember(helper.absolutePos(placedAt));
        var item = PiglinQuests.heirloom(player);
        helper.assertTrue(PiglinQuests.plant(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), 4, item, player.getRandom()),
                "planted somewhere");
        var barrel = (net.minecraft.world.Container) helper.getBlockEntity(worldAt);
        var chest = (net.minecraft.world.Container) helper.getBlockEntity(placedAt);
        boolean inBarrel = false, inChest = false;
        for (int i = 0; i < barrel.getContainerSize(); i++) inBarrel |= PiglinQuests.isHeirloom(barrel.getItem(i));
        for (int i = 0; i < chest.getContainerSize(); i++) inChest |= PiglinQuests.isHeirloom(chest.getItem(i));
        helper.assertTrue(inBarrel && !inChest, "in the world's barrel, never a container a player placed");
        dev.villagechatter.life.Misdeeds.PlacedContainers.get(helper.getLevel()).forget(helper.absolutePos(placedAt));
        helper.succeed();
    }
}

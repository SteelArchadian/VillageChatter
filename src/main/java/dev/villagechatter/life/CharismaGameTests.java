package dev.villagechatter.life;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.villagechatter.api.SpeechCheck;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Charisma (api.SpeechCheck): what you wear, how clean you are, and being a hero. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class CharismaGameTests {
    private CharismaGameTests() {}

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "charming"));
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    /** Fine gear impresses; grime puts people off; heroes talk their way through more easily. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void charismaSources(GameTestHelper helper) {
        var player = player(helper);
        double plain = SpeechCheck.charisma(player);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        double dressed = SpeechCheck.charisma(player);
        helper.assertTrue(dressed > plain, "finery helps");
        Grime.add(player, 80, 0);
        double filthy = SpeechCheck.charisma(player);
        helper.assertTrue(filthy < dressed - 30, "filth hurts");
        double persuade = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player);
        player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 200));
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player) > persuade * 1.2, "heroes persuade");
        helper.assertTrue(SpeechCheck.impress(player) >= 0.05 && SpeechCheck.impress(player) <= 0.8, "impress stays in bounds");
        helper.succeed();
    }

    /** Looking good helps persuading; looking dangerous helps threats; money doesn't care. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void charismaAdjusts(GameTestHelper helper) {
        var player = player(helper);
        double base = 0.3;
        double persuadeBefore = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, base, player);
        double menaceBefore = SpeechCheck.adjust(SpeechCheck.Skill.INTIMIDATE, base, player);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, base, player) > persuadeBefore, "finery persuades");
        Grime.add(player, 0, 60);
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.INTIMIDATE, base, player) > menaceBefore, "armor and blood scare");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.BRIBE, base, player) == base, "bribes are about money");
        helper.succeed();
    }

    /** Alms to a beggar earn a blessing that helps speech checks, but not bribes. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void beggarBlesses(GameTestHelper helper) {
        var player = player(helper);
        var beggar = helper.spawn(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(3, 1, 2));
        beggar.setNoAi(true);
        beggar.setVillagerData(beggar.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.NITWIT));
        double before = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player);
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
        dev.villagechatter.reputation.Gifts.give(player, beggar, 0);
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 1, "the beggar takes one emerald");
        helper.assertTrue(Charity.blessed(player), "the beggar blesses you");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player) > before, "blessed words persuade");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.BRIBE, 0.3, player) == 0.3, "bribes are still about money");
        helper.succeed();
    }

    /** Speech grows with practice, slower near the top, never past 100, and makes checks likelier (not bribes). */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void speechPractice(GameTestHelper helper) {
        var player = player(helper);
        double before = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player);
        Speech.practice(player, 10);
        double low = Speech.skill(player);
        helper.assertTrue(low > 9 && low <= 10, "practice raises the skill");
        Speech.set(player, 90);
        Speech.practice(player, 10);
        helper.assertTrue(Speech.skill(player) - 90 < low / 2, "the top levels come slower");
        for (int i = 0; i < 500; i++) Speech.practice(player, 10);
        helper.assertTrue(Speech.skill(player) == 100, "capped at 100");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player) > before * 1.4, "skill persuades");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.BRIBE, 0.3, player) == 0.3, "bribes are about money");
        helper.succeed();
    }

    /** Each side judges you differently: piglins look for gold, a Hero wins villagers over and gets nowhere with pillagers. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void checksByFaction(GameTestHelper helper) {
        var player = player(helper);
        var VILLAGE = dev.villagechatter.api.Factions.Faction.VILLAGE;
        var PIGLIN = dev.villagechatter.api.Factions.Faction.PIGLIN;
        var PILLAGER = dev.villagechatter.api.Factions.Faction.PILLAGER;
        double piglinPlain = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, PIGLIN);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, PIGLIN) == piglinPlain, "piglins don't care for diamonds");
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, PIGLIN) > piglinPlain, "gold impresses piglins");
        double village = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, VILLAGE);
        double pillager = SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, PILLAGER);
        player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 200));
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, VILLAGE) > village, "villagers love a hero");
        helper.assertTrue(SpeechCheck.adjust(SpeechCheck.Skill.PERSUADE, 0.3, player, PILLAGER) < pillager, "pillagers don't");
        helper.succeed();
    }

    /** The road dirties you; water washes it off. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void grimeWashes(GameTestHelper helper) {
        var player = player(helper);
        Grime.add(player, 40, 20);
        helper.assertTrue(Grime.grime(player) == 60 && Grime.blood(player) == 20, "blood counts as grime too");
        Grime.washOrDirty(player); // dry land, no rain in a test world
        helper.assertTrue(Grime.grime(player) > 60, "the road dirties you");
        Grime.add(player, -4, -4); // what a second in water does
        helper.assertTrue(Grime.grime(player) < 60 && Grime.blood(player) == 16, "water washes");
        helper.succeed();
    }
}

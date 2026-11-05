package dev.villagechatter.quests.test;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.Discounts;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The jobless-villager quest. Run with {@code ./gradlew runGameTestServer}. */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class JobGameTests {
    private JobGameTests() {}

    /** Ask, accept, the villager gets a job, talk: rewarded, discount at the new shop, quest done. */
    @GameTest(template = "empty", timeoutTicks = 60)
    @SuppressWarnings("removal")
    public static void jobFound(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        villager.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.moveTo(pos.x, pos.y, pos.z);

        QuestDefinition def = QuestDefinitions.get(QuestsModule.id("job_seeker"));
        helper.assertTrue(def != null && def.isJob(), "job_seeker quest should be loaded");
        helper.assertTrue(def.professions().contains(net.minecraft.resources.ResourceLocation.withDefaultNamespace("none")),
                "job quests should only fit jobless villagers");
        QuestManager.tryOffer(villager, def);
        helper.assertTrue(QuestManager.talk(player, villager), "jobless villager should ask for help");
        QuestManager.answer(player, villager.getUUID(), QuestManager.ACCEPT);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        helper.assertTrue(quests.quests.size() == 1, "accepting should start the job hunt");

        helper.assertFalse(QuestManager.talk(player, villager), "nothing to say while still jobless (note in hand)");

        // A workstation turns up and they claim it.
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getOffers().add(new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 16, 2, 0.05F));
        helper.assertTrue(QuestManager.talk(player, villager), "employed villager should thank you");

        helper.assertTrue(quests.quests.isEmpty(), "job quest should be finished");
        helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 2, "should get a couple of emeralds");
        helper.assertTrue(player.totalExperience > 0, "should get some XP");
        helper.assertTrue(player.getData(QuestRegistries.DISCOUNTS).byVillager.containsKey(villager.getUUID()), "discount should be granted");

        MerchantOffer offer = villager.getOffers().get(villager.getOffers().size() - 1); // the 20-wheat one added above
        int before = offer.getCostA().getCount();
        helper.assertTrue(Discounts.apply(player, villager), "discount should apply at their shop");
        helper.assertTrue(offer.getCostA().getCount() < before, "price should drop, was " + before + " now " + offer.getCostA().getCount());
        helper.succeed();
    }
}

package dev.villagechatter.quests.quest;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The quest flow: offers, the standalone chat prompts, accepting, handing in, returning, lying, deadlines. */
public final class QuestManager {
    private QuestManager() {}

    public static final String ACCEPT = "accept", DECLINE = "decline", TRADE = "trade", RETURN = "return", KEEP = "keep",
            DELIVER = "deliver", LATER = "later", QUIT = "quit";
    /** How many failed quests stay in the journal. */
    private static final int FAILED_KEPT = 8;

    /** Answers via chat buttons must come from this close to the villager. */
    private static final double ANSWER_DISTANCE = 8.0;
    private static final int OFFER_SCAN_INTERVAL = 100;
    private static final double OFFER_SCAN_RADIUS = 48.0;
    private static final double GRUDGE_RADIUS = 16.0;
    /** After a player says "not now" to an errand, the villager asks around elsewhere for half a day. */
    private static final long ERRAND_DECLINE_COOLDOWN = 12000;

    // ------------------------------------------------------------------ right-click (chat fallback when the dialogue is off)

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Villager villager)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND || !QuestConfig.ENABLED.get()) return;
        if (!QuestConfig.STANDALONE_RIGHT_CLICK.get() || player.isShiftKeyDown() || player.isSpectator()) return;
        if (!villager.isAlive() || villager.isSleeping() || villager.isTrading()) return;
        ItemStack held = player.getMainHandItem();
        if (held.is(Items.NAME_TAG) || held.is(Items.LEAD) || held.getItem() instanceof SpawnEggItem) return;

        if (talk(player, villager)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    /** Says whatever quest-related thing this villager has for this player. @return false if there was nothing. */
    public static boolean talk(ServerPlayer player, Villager villager) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        List<ActiveQuest> fromGiver = quests.fromGiver(villager.getUUID());
        Component name = villager.getName();

        // 0. Someone else died and the giver owes you for your time.
        Component compensation = payCompensation(player, villager);
        if (compensation != null) {
            QuestText.say(player, name, compensation);
            return true;
        }

        // 0b. You found someone's lost buddy.
        Component rescued = Rescue.talk(player, villager);
        if (rescued != null) {
            QuestText.say(player, name, rescued);
            return true;
        }

        // 1. Bringing the heirloom back (also late: no reward, but no grudge either).
        for (ActiveQuest quest : fromGiver) {
            int slot = findHeirloom(player, quest.instance);
            if (slot < 0) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            String key = quest.isActive() ? "villagequests.say.found" : "villagequests.say.found_late";
            QuestText.say(player, name, Component.translatable(key, stack.getHoverName()));
            player.sendSystemMessage(QuestText.buttons(
                    QuestText.button("villagequests.button.return", "villagequests.button.return.hover", ChatFormatting.GREEN, villager.getUUID(), RETURN),
                    QuestText.button("villagequests.button.keep", "villagequests.button.keep.hover", ChatFormatting.RED, villager.getUUID(), KEEP)));
            lookAt(villager, player, SoundEvents.VILLAGER_AMBIENT);
            return true;
        }

        // 2. A jobless villager you promised to help has found work; or the thing you were asked to build is there.
        if (tryCompleteJob(player, villager)) return true;
        if (Build.tryComplete(player, villager)) return true;

        // 3. Bringing what an errand asked for.
        for (ActiveQuest quest : fromGiver) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isErrand() || !quest.isActive() || countMatching(player, def.request()) < quest.count) continue;
            QuestText.say(player, name, Component.translatable("villagequests.say.errand_ready", requestText(def, quest.count)));
            player.sendSystemMessage(QuestText.buttons(
                    QuestText.button("villagequests.button.deliver", "villagequests.button.deliver.hover", ChatFormatting.GREEN, villager.getUUID(), DELIVER),
                    QuestText.button("villagequests.button.later", "villagequests.button.later.hover", ChatFormatting.GRAY, villager.getUUID(), LATER)));
            lookAt(villager, player, SoundEvents.VILLAGER_AMBIENT);
            return true;
        }

        // 5. A new request to share.
        GiverData giver = villager.getData(QuestRegistries.GIVER);
        if (giver.hasOffer() && fromGiver.isEmpty() && quests.activeCount() < QuestConfig.MAX_ACTIVE_QUESTS.get()) {
            QuestDefinition def = QuestDefinitions.get(ResourceLocation.parse(giver.offer()));
            if (def == null || !def.givers().allows(villager.isBaby()) || (def.isJob() && hasJob(villager))) {
                villager.setData(QuestRegistries.GIVER, giver.cleared(giver.nextOfferTime(), giver.lastQuest()));
                return false;
            }
            QuestText.say(player, name, story(def, villager, giver.count()));
            Component accept = QuestText.button("villagequests.button.accept", "villagequests.button.accept.hover", ChatFormatting.GREEN, villager.getUUID(), ACCEPT);
            Component decline = QuestText.button("villagequests.button.decline", "villagequests.button.decline.hover", ChatFormatting.GRAY, villager.getUUID(), DECLINE);
            player.sendSystemMessage(canTrade(villager)
                    ? QuestText.buttons(accept, decline, QuestText.button("villagequests.button.trade", "villagequests.button.trade.hover", ChatFormatting.AQUA, villager.getUUID(), TRADE))
                    : QuestText.buttons(accept, decline));
            lookAt(villager, player, SoundEvents.VILLAGER_AMBIENT);
            return true;
        }
        return false;
    }

    static Component story(QuestDefinition def, Villager villager, int count) {
        Component name = villager.getName();
        String key = def.variant(def.text().story(), villager.getUUID());
        if (def.isJob()) return Component.translatable(key, name);
        if (def.isBuild()) return Component.translatable(key, name, count);
        if (def.isRescue()) return Component.translatable(key, name, Component.literal(Rescue.buddyName(villager.getUUID(), def)));
        if (def.isLostPet()) return Component.translatable(key, LostPet.storyArgs(def, villager).toArray());
        if (def.isErrand() || def.isCourier()) return Component.translatable(key, name, count, def.request().displayName());
        Component item = Component.translatable(def.text().itemName(), name);
        Component guardian = Component.translatable(def.text().guardianName(), name);
        return Component.translatable(key, name, item, guardian);
    }

    /** "15 × Poppy" */
    public static Component requestText(QuestDefinition def, int count) {
        return Component.translatable("villagequests.request", count, def.request().displayName()).withStyle(ChatFormatting.AQUA);
    }

    private static boolean canTrade(Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return !villager.isBaby() && profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT
                && !villager.getOffers().isEmpty();
    }

    /** A chat button was clicked (runs /villagequests answer). */
    public static void answer(ServerPlayer player, UUID villagerId, String choice) {
        Entity entity = player.serverLevel().getEntity(villagerId);
        if (!(entity instanceof Villager villager) || !villager.isAlive()
                || villager.distanceToSqr(player) > ANSWER_DISTANCE * ANSWER_DISTANCE) {
            player.sendSystemMessage(Component.translatable("villagequests.msg.too_far").withStyle(ChatFormatting.GRAY));
            return;
        }
        switch (choice) {
            case ACCEPT -> accept(player, villager);
            case DECLINE -> decline(player, villager);
            case TRADE -> {
                player.swing(InteractionHand.MAIN_HAND, true);
                villager.interact(player, InteractionHand.MAIN_HAND);
            }
            case RETURN -> settle(player, villager, true);
            case KEEP -> settle(player, villager, false);
            case DELIVER -> deliver(player, villager);
            case LATER -> QuestText.say(player, villager.getName(), Component.translatable("villagequests.say.later"));
            case QUIT -> {
                ActiveQuest quest = player.getData(QuestRegistries.PLAYER_QUESTS).fromGiver(villager.getUUID()).stream()
                        .filter(q -> q.isActive() || q.state == ActiveQuest.State.EXPIRED).findFirst().orElse(null);
                if (quest != null) {
                    String line = "villagequests.say.quit." + Math.floorMod(villager.getUUID().hashCode(), 3);
                    QuestText.say(player, villager.getName(), Component.translatable(line));
                    quit(player, quest, villager);
                }
            }
            case Courier.DELIVER -> {
                Component said = Courier.deliver(player, villager);
                if (said != null) QuestText.say(player, villager.getName(), said);
            }
            case Courier.RETURN_REPLY -> {
                Component said = Courier.returnReply(player, villager);
                if (said != null) QuestText.say(player, villager.getName(), said);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ accept / decline

    private static void accept(ServerPlayer player, Villager villager) {
        GiverData giver = villager.getData(QuestRegistries.GIVER);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        if (!giver.hasOffer() || !quests.fromGiver(villager.getUUID()).isEmpty()) return;
        if (quests.activeCount() >= QuestConfig.MAX_ACTIVE_QUESTS.get()) {
            player.sendSystemMessage(Component.translatable("villagequests.msg.too_many").withStyle(ChatFormatting.GRAY));
            return;
        }
        QuestDefinition def = QuestDefinitions.get(ResourceLocation.parse(giver.offer()));
        if (def == null) return;

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Component name = villager.getName().copy();
        TargetFinder.Target target;
        if (def.isCourier()) {
            // A real neighbouring village; if there's none in reach, the villager shrugs it off for a day.
            Courier.Destination destination = Courier.find(level, villager.blockPosition(), def, player);
            if (destination == null) {
                QuestText.say(player, name, Component.translatable("villagequests.courier.nowhere"));
                villager.setData(QuestRegistries.GIVER, giver.cleared(now + 24000, giver.lastQuest()));
                return;
            }
            target = new TargetFinder.Target(destination.pos(), Courier.kind(destination));
        } else {
            target = def.hasLocation()
                    ? TargetFinder.find(level, villager.blockPosition(), def.location(), level.random)
                    : new TargetFinder.Target(villager.blockPosition(), def.isJob() ? "job" : def.isBuild() ? "build" : "errand");
        }
        int count = def.isErrand() || def.isCourier() || def.isBuild() ? Math.max(1, giver.count()) : 0;
        BlockPos village = dev.villagechatter.reputation.Villages.center(level, villager);
        dev.villagechatter.reputation.Villages.learnName(level, village);
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), villager.getUUID(), name, level.dimension(),
                village, target.pos(), target.kind(), now, now + def.deadlineTicks(),
                ActiveQuest.State.ACTIVE, false, 0, count);
        quests.quests.add(quest);

        long cooldown = Math.round(QuestConfig.OFFER_COOLDOWN_DAYS.get() * 24000);
        villager.setData(QuestRegistries.GIVER, giver.cleared(now + cooldown, def.id().toString()));

        String thanksKey = def.variant(def.text().thanks(), villager.getUUID());
        Component buddy = Component.literal(Rescue.buddyName(villager.getUUID(), def));
        if (def.isCourier()) Courier.tryBindNow(player, quest);  // destination loaded? then they can name the person
        Component thanks = def.isBuild()
                ? Component.translatable(thanksKey, name, count, QuestText.duration(def.deadlineTicks()))
                : def.isCourier()
                ? Component.translatable(thanksKey, name, count, def.request().displayName(),
                        Courier.recipientLabel(player, quest), Courier.direction(quest.giverPos, quest.target),
                        Courier.roughDistance(quest.giverPos, quest.target), QuestText.duration(def.deadlineTicks()))
                : def.isLostPet()
                ? Component.translatable(thanksKey, name, LostPet.petName(villager.getUUID(), def),
                        QuestText.placePhrase(target.kind(), villager.blockPosition(), target.pos()), QuestText.roughCoords(target.pos()),
                        QuestText.duration(def.deadlineTicks()))
                : def.isRescue()
                ? Component.translatable(thanksKey, name, buddy,
                        QuestText.placePhrase(target.kind(), villager.blockPosition(), target.pos()), QuestText.roughCoords(target.pos()),
                        QuestText.duration(def.deadlineTicks()))
                : def.isJob()
                ? Component.translatable(thanksKey, name, QuestText.duration(def.deadlineTicks()))
                : def.isErrand()
                ? Component.translatable(thanksKey, name, count, def.request().displayName(), QuestText.duration(def.deadlineTicks()))
                : Component.translatable(thanksKey, name,
                        QuestText.placePhrase(target.kind(), villager.blockPosition(), target.pos()), QuestText.roughCoords(target.pos()),
                        QuestText.duration(def.deadlineTicks()));
        QuestText.say(player, name, thanks);
        if (def.isRescue()) Rescue.tip(player);
        if (def.isCourier()) give(player, Courier.makePackage(def, quest, Courier.recipientLabel(player, quest)));
        lookAt(villager, player, SoundEvents.VILLAGER_YES);
    }

    private static void decline(ServerPlayer player, Villager villager) {
        QuestText.say(player, villager.getName(), Component.translatable("villagequests.say.declined"));
        lookAt(villager, player, SoundEvents.VILLAGER_NO);
        // Errands are small: rather than nag, the villager drops it and asks around later.
        // Heirlooms matter too much to them; that offer stays open.
        GiverData giver = villager.getData(QuestRegistries.GIVER);
        QuestDefinition def = giver.hasOffer() ? QuestDefinitions.get(ResourceLocation.parse(giver.offer())) : null;
        if (def != null && (def.isErrand() || def.isCourier())) {
            villager.setData(QuestRegistries.GIVER, giver.cleared(player.serverLevel().getGameTime() + ERRAND_DECLINE_COOLDOWN, giver.offer()));
        }
    }

    // ------------------------------------------------------------------ jobs

    /** If a jobless villager you're helping has found work, they thank and reward you. @return true if so. */
    public static boolean tryCompleteJob(ServerPlayer player, Villager villager) {
        if (!hasJob(villager)) return false;
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        for (ActiveQuest quest : quests.fromGiver(villager.getUUID())) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isJob() || !quest.isActive()) continue;
            completeJob(player, villager, quests, quest, def);
            return true;
        }
        return false;
    }

    static boolean hasJob(Villager villager) {
        VillagerProfession p = villager.getVillagerData().getProfession();
        return p != VillagerProfession.NONE && p != VillagerProfession.NITWIT;
    }

    /** "I have a job now!" XP, a couple of emeralds, a day's discount at their new shop, and village goodwill. */
    private static void completeJob(ServerPlayer player, Villager villager, PlayerQuests quests, ActiveQuest quest, QuestDefinition def) {
        QuestDefinition.Rewards r = def.rewards();
        int emeralds = Mth.nextInt(player.getRandom(), r.emeraldsMin(), Math.max(r.emeraldsMin(), r.emeraldsMax()));
        if (emeralds > 0) give(player, new ItemStack(Items.EMERALD, emeralds));
        if (r.xp() > 0) player.giveExperiencePoints(r.xp());
        giveLoot(player, villager, r);
        Discounts.grant(player, villager, r.discountDays(), r.discount());
        Reputation.addGoodwill(player, villager, r.goodwillGiver(), r.goodwillVillage());

        QuestText.say(player, villager.getName(), Component.translatable(def.variant(def.text().done(), villager.getUUID()),
                villager.getName(), professionName(villager)));
        lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
        finish(player, quests, quest);
    }

    /** "Farmer", "Librarian", or a modded profession's name (same lang key vanilla uses for the villager's name). */
    public static Component professionName(Villager villager) {
        ResourceLocation id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        return Component.translatable(villager.getType().getDescriptionId() + "." + id.getPath());
    }

    // ------------------------------------------------------------------ errands

    private static void deliver(ServerPlayer player, Villager villager) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        for (ActiveQuest quest : quests.fromGiver(villager.getUUID())) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isErrand() || !quest.isActive()) continue;
            if (countMatching(player, def.request()) < quest.count) {
                QuestText.say(player, villager.getName(), Component.translatable("villagequests.say.errand_short", requestText(def, quest.count)));
                return;
            }
            takeMatching(player, def.request(), quest.count);

            QuestDefinition.Rewards r = def.rewards();
            int emeralds = (int) Math.round(quest.count * r.emeraldsPerItem());
            if (r.emeraldsMax() > 0) emeralds += Mth.nextInt(player.getRandom(), r.emeraldsMin(), r.emeraldsMax());
            if (emeralds > 0) give(player, new ItemStack(Items.EMERALD, emeralds));
            int xp = (int) Math.round(quest.count * r.xpPerItem());
            if (xp > 0) player.giveExperiencePoints(xp);
            giveLoot(player, villager, r);
            Reputation.addGoodwill(player, villager, r.goodwillGiver(), r.goodwillVillage());

            QuestText.say(player, villager.getName(), Component.translatable(def.variant(def.text().done(), villager.getUUID()), villager.getName()));
            if (def.celebrateGolems()) celebrateGolems(villager);
            lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
            finish(player, quests, quest);
            return;
        }
    }

    /** The kid runs off to share the flowers: every golem around gets hearts. */
    private static void celebrateGolems(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, villager.getBoundingBox().inflate(Reputation.VILLAGE_RADIUS))) {
            level.sendParticles(ParticleTypes.HEART, golem.getX(), golem.getEyeY() + 0.5, golem.getZ(), 4, 0.4, 0.3, 0.4, 0.0);
            golem.offerFlower(true);
        }
    }

    public static int countMatching(ServerPlayer player, QuestDefinition.Request request) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (request.matches(stack) && !stack.has(QuestRegistries.HEIRLOOM.get()) && !stack.has(QuestRegistries.PARCEL.get())) total += stack.getCount();
        }
        return total;
    }

    static void takeMatching(ServerPlayer player, QuestDefinition.Request request, int amount) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!request.matches(stack) || stack.has(QuestRegistries.HEIRLOOM.get()) || stack.has(QuestRegistries.PARCEL.get())) continue;
            int take = Math.min(amount, stack.getCount());
            stack.shrink(take);
            amount -= take;
        }
        inv.setChanged();
    }

    // ------------------------------------------------------------------ heirlooms: return / keep

    private static void settle(ServerPlayer player, Villager villager, boolean giveBack) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        for (ActiveQuest quest : quests.fromGiver(villager.getUUID())) {
            int slot = findHeirloom(player, quest.instance);
            if (slot < 0) continue;
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            Component name = villager.getName();
            ItemStack heirloom = player.getInventory().getItem(slot);
            boolean late = !quest.isActive();

            if (giveBack) {
                Component itemName = heirloom.getHoverName();
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                if (late) {
                    QuestText.say(player, name, Component.translatable("villagequests.say.returned_late", itemName));
                } else {
                    QuestText.say(player, name, Component.translatable("villagequests.say.returned", itemName));
                    if (def != null) {
                        giveHeirloomRewards(player, villager, def);
                        Reputation.adjust(player, villager, def.rewards().repReturned(), true);
                        // Word gets around: a little for the rest of the village.
                        Reputation.addGoodwill(player, villager, 0, 1.0);
                    }
                }
                lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
            } else {
                QuestText.say(player, name, Component.translatable(late ? "villagequests.say.lied_late" : "villagequests.say.lied"));
                int penalty = late ? quest.pendingRep : (def == null ? 0 : def.rewards().repLied());
                Reputation.adjust(player, villager, penalty, false);
                // It's yours now; it only remembers where it came from.
                heirloom.remove(QuestRegistries.HEIRLOOM.get());
                lookAt(villager, player, SoundEvents.VILLAGER_NO);
            }
            finish(player, quests, quest);
            return;
        }
    }

    private static void giveHeirloomRewards(ServerPlayer player, Villager villager, QuestDefinition def) {
        QuestDefinition.Rewards rewards = def.rewards();
        int emeralds = Mth.nextInt(player.getRandom(), rewards.emeraldsMin(), Math.max(rewards.emeraldsMin(), rewards.emeraldsMax()));
        if (emeralds > 0) give(player, new ItemStack(Items.EMERALD, emeralds));
        giveLoot(player, villager, rewards);
    }

    private static void giveLoot(ServerPlayer player, Villager villager, QuestDefinition.Rewards rewards) {
        if (rewards.lootTable() == null) return;
        ServerLevel level = player.serverLevel();
        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, rewards.lootTable()));
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.GIFT);
        table.getRandomItems(params).forEach(stack -> give(player, stack));
    }

    /** Removes the quest and any notes for it. */
    private static void finish(ServerPlayer player, PlayerQuests quests, ActiveQuest quest) {
        quests.quests.remove(quest);
        removeNotes(player, quest);
    }

    static void removeNotesFor(ServerPlayer player, ActiveQuest quest) {
        removeNotes(player, quest);
    }

    private static void removeNotes(ServerPlayer player, ActiveQuest quest) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            NoteData note = inv.getItem(i).get(QuestRegistries.NOTE.get());
            if (note != null && note.instance().equals(quest.instance)) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!QuestConfig.ENABLED.get()) return;
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % 20 != 0) return;
        long now = server.overworld().getGameTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
            if (!quests.quests.isEmpty()) {
                checkDeadlines(player, quests, now);
                Courier.tickPlayer(player, quests);
                settleGrudges(player, quests);
                GuardianManager.tickPlayer(player, quests, now);
                quests.pruneFailed(FAILED_KEPT);
                if (tick % MOVE_CHECK_INTERVAL == 0) checkMovedGivers(player, quests);
            }
            if (tick % 40 == 0) showOfferMarkers(player);
            if (tick % OFFER_SCAN_INTERVAL == 0) rollOffers(player, now);
        }
        GuardianManager.tickAll(server, now);
        GuardQuests.tick(server, tick);
    }

    private static void checkDeadlines(ServerPlayer player, PlayerQuests quests, long now) {
        Iterator<ActiveQuest> it = quests.quests.iterator();
        while (it.hasNext()) {
            ActiveQuest quest = it.next();
            if (!quest.isActive() || now < quest.deadline) continue;
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || def.isMinor()) {
                // Small favors just lapse: no hard feelings.
                player.sendSystemMessage(Component.translatable("villagequests.msg.errand_lapsed", quest.giverName).withStyle(ChatFormatting.GRAY));
                fail(player, quest, "villagequests.fail.lapsed");
                continue;
            }
            quest.state = ActiveQuest.State.EXPIRED;
            quest.pendingRep = def.rewards().repExpired();
            player.sendSystemMessage(Component.translatable("villagequests.msg.expired", quest.giverName).withStyle(ChatFormatting.RED));
        }
    }

    /** Expired quests: the giver lets you know (and remembers) the next time you're close. */
    private static void settleGrudges(ServerPlayer player, PlayerQuests quests) {
        Iterator<ActiveQuest> it = quests.quests.iterator();
        while (it.hasNext()) {
            ActiveQuest quest = it.next();
            if (quest.state != ActiveQuest.State.EXPIRED) continue;
            if (player.level().dimension() != quest.dimension) continue;
            if (!(player.serverLevel().getEntity(quest.giver) instanceof Villager villager)) continue;
            if (villager.distanceToSqr(player) > GRUDGE_RADIUS * GRUDGE_RADIUS) continue;
            // Holding the heirloom? Leave it open so they can still bring it back late.
            if (findHeirloom(player, quest.instance) >= 0) continue;
            if (quest.pendingRep != 0) {
                QuestText.say(player, villager.getName(), Component.translatable("villagequests.say.grudge"));
                Reputation.adjust(player, villager, quest.pendingRep, false);
                lookAt(villager, player, SoundEvents.VILLAGER_NO);
                quest.pendingRep = 0;
            }
            fail(player, quest, "villagequests.fail.expired");
        }
    }

    private static void showOfferMarkers(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (Villager villager : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(24.0))) {
            if (!villager.getData(QuestRegistries.GIVER).hasOffer()) continue;
            level.sendParticles(player, ParticleTypes.WAX_ON, false, villager.getX(), villager.getY() + villager.getBbHeight() + 0.5,
                    villager.getZ(), 2, 0.15, 0.1, 0.15, 0.0);
        }
    }

    /** Villagers near players sometimes come up with a request. */
    private static void rollOffers(ServerPlayer player, long now) {
        ServerLevel level = player.serverLevel();
        double perDay = QuestConfig.OFFER_CHANCE_PER_DAY.get();
        if (perDay <= 0 || QuestDefinitions.all().isEmpty()) return;
        double chance = 1.0 - Math.pow(1.0 - Math.min(perDay, 0.9999), OFFER_SCAN_INTERVAL / 24000.0);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(OFFER_SCAN_RADIUS))) {
            GiverData data = villager.getData(QuestRegistries.GIVER);
            if (data.hasOffer() || now < data.nextOfferTime()) continue;
            if (level.random.nextDouble() >= chance) continue;
            tryOffer(villager, null);
        }
    }

    /**
     * Gives the villager a pending request. {@code forced} skips chance, village limits and variety rules (debug command).
     * Variety: a villager won't repeat the last quest someone took from them, and two villagers in one village
     * won't ask for the same thing at the same time.
     */
    @Nullable
    public static QuestDefinition tryOffer(Villager villager, @Nullable QuestDefinition forced) {
        ServerLevel level = (ServerLevel) villager.level();
        ResourceLocation profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        GiverData data = villager.getData(QuestRegistries.GIVER);
        QuestDefinition def = forced;
        if (def == null) {
            if (!villager.isBaby() && QuestConfig.EXCLUDED_PROFESSIONS.get().contains(profession.toString())) return null;
            List<Villager> village = level.getEntitiesOfClass(Villager.class, villager.getBoundingBox().inflate(Reputation.VILLAGE_RADIUS));
            Set<ResourceLocation> exclude = new HashSet<>();
            int offers = 0;
            for (Villager v : village) {
                GiverData d = v.getData(QuestRegistries.GIVER);
                if (d.hasOffer()) {
                    offers++;
                    exclude.add(ResourceLocation.parse(d.offer()));
                }
            }
            if (offers >= QuestConfig.MAX_OFFERS_PER_VILLAGE.get()) return null;
            if (!data.lastQuest().isEmpty()) exclude.add(ResourceLocation.parse(data.lastQuest()));
            def = QuestDefinitions.pickFor(profession, villager.isBaby(), exclude, level.random);
            // Build requests only make sense while the village actually lacks the thing.
            for (int retry = 0; retry < 4 && def != null && def.isBuild() && !Build.offerable(def, villager); retry++) {
                exclude.add(def.id());
                def = QuestDefinitions.pickFor(profession, villager.isBaby(), exclude, level.random);
            }
            if (def != null && def.isBuild() && !Build.offerable(def, villager)) def = null;
            if (def == null) {
                // Nothing fits right now; don't ask again for a day.
                villager.setData(QuestRegistries.GIVER, data.cleared(level.getGameTime() + 24000, data.lastQuest()));
                return null;
            }
        }
        int count = def.isErrand() || def.isCourier() ? Mth.nextInt(level.random, def.request().min(), def.request().max())
                : def.isBuild() ? Build.target(def, villager) : 0;
        villager.setData(QuestRegistries.GIVER, data.withOffer(def.id().toString(), count));
        return def;
    }

    // ------------------------------------------------------------------ things going wrong

    /**
     * A quest heirloom lying in the world got destroyed (lava, fire, cactus, the void...). Nobody's fault: the
     * quest fails and the giver makes it up to you, a little. Picked up items also "leave", so only real losses count.
     */
    public static void onEntityLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item) || event.getLevel().isClientSide()) return;
        HeirloomData data = item.getItem().get(QuestRegistries.HEIRLOOM.get());
        if (data == null) return;
        net.minecraft.world.entity.Entity.RemovalReason reason = item.getRemovalReason();
        boolean destroyed = reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED
                || (reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED && item.getY() < event.getLevel().getMinBuildHeight());
        if (!destroyed || !(event.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(data.owner());
        if (owner == null) return;
        owner.getData(QuestRegistries.PLAYER_QUESTS).byInstance(data.instance())
                .filter(q -> q.isActive() || q.state == ActiveQuest.State.EXPIRED)
                .ifPresent(q -> failCompensated(owner, q, "item_lost", item.getItem().getHoverName().getString()));
    }

    /** How often (ticks) to check whether givers have moved to another village. Rare on purpose. */
    private static final int MOVE_CHECK_INTERVAL = 6000;

    /** Givers who settled in a different village: the quest (and journal) follow them. */
    private static void checkMovedGivers(ServerPlayer player, PlayerQuests quests) {
        for (ActiveQuest quest : quests.quests) {
            if (quest.state == ActiveQuest.State.FAILED || player.server.getLevel(quest.dimension) == null) continue;
            ServerLevel level = player.server.getLevel(quest.dimension);
            if (!(level.getEntity(quest.giver) instanceof Villager giver)) continue;
            BlockPos now = dev.villagechatter.reputation.Villages.center(level, giver);
            double dx = now.getX() - quest.giverPos.getX(), dz = now.getZ() - quest.giverPos.getZ();
            if (dx * dx + dz * dz < 96 * 96) continue;
            quest.giverPos = now;
            dev.villagechatter.reputation.Villages.learnName(level, now);
            player.sendSystemMessage(Component.translatable("villagequests.msg.giver_moved", quest.giverName,
                    dev.villagechatter.reputation.Villages.label(level, now)).withStyle(ChatFormatting.GRAY));
        }
    }

    // ------------------------------------------------------------------ deaths

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) return;
        Courier.onVillagerDeath(level, villager);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
            for (ActiveQuest quest : List.copyOf(quests.fromGiver(villager.getUUID()))) {
                player.sendSystemMessage(Component.translatable("villagequests.msg.giver_died", quest.giverName).withStyle(ChatFormatting.GRAY));
                fail(player, quest, "villagequests.fail.giver_died");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The quest's name, e.g. "Aldric's Family Sword" or "15 Poppies for the Golems". */
    public static Component title(QuestDefinition def, ActiveQuest quest) {
        return def.isBuild()
                ? Component.translatable(def.text().title(), quest.giverName, quest.count)
                : def.isCourier()
                ? Component.translatable(def.text().title(), quest.giverName, quest.count, def.request().displayName(),
                        Component.translatable("villagequests.courier.a_profession", Courier.recipientName(Courier.recipientOf(quest))),
                        Courier.direction(quest.giverPos, quest.target),
                        Courier.roughDistance(quest.giverPos, quest.target))
                : def.isRescue()
                ? Component.translatable(def.text().title(), quest.giverName, Component.literal(Rescue.buddyName(quest.giver, def)))
                : def.isLostPet()
                ? Component.translatable(def.text().title(), quest.giverName, LostPet.petName(quest.giver, def), LostPet.speciesWord(quest.giver, def))
                : def.isJob()
                ? Component.translatable(def.text().title(), quest.giverName)
                : def.isErrand()
                ? Component.translatable(def.text().title(), quest.giverName, quest.count, def.request().displayName())
                : Component.translatable(def.text().title(), quest.giverName);
    }

    /** One line on where the quest stands, for the journal and /villagequests list. */
    public static Component status(ServerPlayer player, ActiveQuest q, @Nullable QuestDefinition def, long now) {
        Component left = QuestText.duration(Math.max(0, q.deadline - now));
        if (q.state == ActiveQuest.State.EXPIRED) return Component.translatable("villagequests.cmd.status.expired").withStyle(ChatFormatting.RED);
        if (def == null) return left;
        if (def.isBuild()) return Component.translatable("villagequests.journal.status.with_time", Build.status(player, q, def), left);
        if (def.isLostPet()) return q.itemDropped
                ? LostPet.followingStatus(q, def)
                : Component.translatable("villagequests.journal.status.goto", QuestText.kindName(q.kind), QuestText.roughCoords(q.target), left);
        if (def.isRescue()) return q.itemDropped
                ? Component.translatable("villagequests.cmd.status.escorting").withStyle(ChatFormatting.DARK_GREEN)
                : Component.translatable("villagequests.journal.status.goto", QuestText.kindName(q.kind), QuestText.roughCoords(q.target), left);
        if (def.isHeirloom()) return q.itemDropped
                ? Component.translatable("villagequests.journal.status.return", q.giverName, left)
                : Component.translatable("villagequests.journal.status.goto", QuestText.kindName(q.kind), QuestText.roughCoords(q.target), left);
        if (def.isJob()) return Component.translatable("villagequests.cmd.status.job", left);
        if (def.isCourier() && q.itemDropped) return Component.translatable("villagequests.journal.status.courier_reply", q.giverName, left);
        if (def.isCourier()) return Component.translatable("villagequests.journal.status.courier",
                Courier.recipientLabel(player, q), Courier.direction(q.giverPos, q.target), Courier.roughDistance(q.giverPos, q.target), left);
        if (def.isErrand()) return Component.translatable("villagequests.cmd.status.errand", countMatching(player, def.request()), q.count, left);
        return left;
    }

    /** Over without success. Stays in the journal (struck through, with the reason) until cleared. */
    public static void fail(ServerPlayer player, ActiveQuest quest, String reasonKey) {
        if (quest.state == ActiveQuest.State.FAILED) return;
        QuestDefinition def = QuestDefinitions.get(quest.questId);
        if (def != null && def.isRescue()) Rescue.release(player.serverLevel(), quest);
        if (def != null && def.isLostPet()) LostPet.release(player, quest);
        if (def != null && def.isCourier()) Courier.unmark(player, quest);
        int slot = findHeirloom(player, quest.instance);
        if (slot >= 0) player.getInventory().getItem(slot).remove(QuestRegistries.HEIRLOOM.get());
        quest.state = ActiveQuest.State.FAILED;
        quest.failReason = reasonKey;
        quest.pendingRep = 0;
        removeNotes(player, quest);
    }

    /**
     * Failed because someone else died (a courier's recipient, a buddy...): not your fault and not the giver's.
     * The journal says so ("Kevin died, ask Steve about it"), and next time you talk to the giver they commiserate and
     * pay a modest compensation for your time. Hook for quest types with a third party: call this when they die.
     */
    public static void failCompensated(ServerPlayer player, ActiveQuest quest, String deceasedName) {
        failCompensated(player, quest, "recipient_died", deceasedName);
    }

    /**
     * Failed through nobody's fault. {@code why} picks the journal text, the chat message and the giver's
     * lines: "recipient_died" (someone else died) or "item_lost" (the quest item burned, fell into the void...).
     */
    public static void failCompensated(ServerPlayer player, ActiveQuest quest, String why, String what) {
        fail(player, quest, "villagequests.fail." + why + "|" + what);
        quest.pendingRep = 1; // FAILED + pendingRep > 0 = compensation owed, see ActiveQuest#compensationOwed
        player.sendSystemMessage(Component.translatable("villagequests.msg." + why, what, quest.giverName)
                .withStyle(ChatFormatting.GRAY));
    }

    /** "Aw, Kevin died? That's awful. Here, for your trouble." @return what the giver says, or null if nothing's owed. */
    @Nullable
    public static Component payCompensation(ServerPlayer player, Villager giver) {
        for (ActiveQuest quest : player.getData(QuestRegistries.PLAYER_QUESTS).quests) {
            if (!quest.giver.equals(giver.getUUID()) || !quest.compensationOwed()) continue;
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            int emeralds = 2;
            if (def != null) {
                QuestDefinition.Rewards r = def.rewards();
                // A mediocre reward: about a third of what the job would have paid.
                double full = (r.emeraldsMin() + r.emeraldsMax()) / 2.0 + quest.count * r.emeraldsPerItem();
                emeralds = Math.max(2, (int) Math.round(full / 3.0));
            }
            give(player, new ItemStack(Items.EMERALD, emeralds));
            player.giveExperiencePoints(5);
            Reputation.addGoodwill(player, giver, 0.5, 0);
            String reason = quest.failReason;
            int bar = reason.indexOf('|');
            String name = bar < 0 ? "" : reason.substring(bar + 1);
            String why = (bar < 0 ? reason : reason.substring(0, bar)).replace("villagequests.fail.", "");
            quest.pendingRep = 0;
            quest.failReason = "villagequests.fail." + why + "_paid|" + name;
            lookAt(giver, player, SoundEvents.VILLAGER_NO);
            String lines = why.equals("item_lost") ? "villagequests.say.compensation_lost." : "villagequests.say.compensation.";
            return Component.translatable(lines + Math.floorMod(giver.getUUID().hashCode(), 3), name);
        }
        return null;
    }

    /**
     * Giving up, from the journal, the dialogue or /villagequests abandon. The giver is a little disappointed
     * (a small goodwill dip, if they're around to hear it), nothing worse. A recovered heirloom is simply yours.
     */
    public static void quit(ServerPlayer player, ActiveQuest quest, @Nullable Villager giver) {
        if (giver == null && player.server.getLevel(quest.dimension) instanceof ServerLevel level
                && level.getEntity(quest.giver) instanceof Villager loaded) {
            giver = loaded;
        }
        if (giver != null) {
            Reputation.addGoodwill(player, giver, -1.0, 0);
            lookAt(giver, player, SoundEvents.VILLAGER_NO);
        } else if (player.server.getLevel(quest.dimension) instanceof ServerLevel level
                && level.getEntity(quest.giver) instanceof net.minecraft.world.entity.Mob guard && GuardQuests.isGiver(guard)) {
            // A guard's request, quit from the journal: a security job pays for what was built.
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            boolean paid = def != null && Security.isPlacement(def) && Security.payPartial(player, guard, quest, def);
            if (!paid) GuardQuests.addGuardGoodwill(player, guard, -1.0);
        } else {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def != null && Security.isPlacement(def)) Security.payPartial(player, null, quest, def);
        }
        fail(player, quest, "villagequests.fail.quit");
    }

    public static int findHeirloom(ServerPlayer player, UUID instance) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            HeirloomData data = inv.getItem(i).get(QuestRegistries.HEIRLOOM.get());
            if (data != null && data.instance().equals(instance)) return i;
        }
        return -1;
    }

    public static int findNote(ServerPlayer player, UUID instance) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            NoteData data = inv.getItem(i).get(QuestRegistries.NOTE.get());
            if (data != null && data.instance().equals(instance)) return i;
        }
        return -1;
    }

    static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    static void lookAt(Villager villager, ServerPlayer player, SoundEvent sound) {
        villager.getLookControl().setLookAt(player);
        villager.playSound(sound, 1.0F, villager.getVoicePitch());
    }

    /** For commands: the quest at a 1-based index in the player's list. */
    @Nullable
    public static ActiveQuest byIndex(ServerPlayer player, int index) {
        List<ActiveQuest> list = player.getData(QuestRegistries.PLAYER_QUESTS).quests;
        return index >= 1 && index <= list.size() ? list.get(index - 1) : null;
    }

    /** /villagequests abandon: same as giving up from the journal. */
    public static void abandon(ServerPlayer player, ActiveQuest quest) {
        quit(player, quest, null);
    }
}

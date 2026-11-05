package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Requests from village guards (Guard Villagers, or any "guard" mob): a new sword, a shield, armor, arrows, beds for
 * the village. A way to earn a guard's trust early on: the reward is mostly goodwill with that guard (which
 * {@link GuardTrust} counts toward letting you into their gear), plus a little for the village.
 *
 * Uses the normal quest data ({@code "givers": "guard"} on errand and build quests), notes, journal and deadlines;
 * only offering, talking and handing in live here, because guards aren't villagers.
 */
public final class GuardQuests {
    private GuardQuests() {}

    private static final String ASK = QuestDialogue.PREFIX + "guard_ask";
    private static final int SCAN_INTERVAL = 100;
    private static final double SCAN_RADIUS = 48.0;
    /** At most this many guards near each other with an open request. */
    private static final int MAX_OPEN_NEARBY = 1;

    /** GuardTrust.isGuard, minus vanilla's (elder) guardians, whose ids also contain "guard". */
    public static boolean isGuard(net.minecraft.world.entity.Entity e) {
        return GuardTrust.isGuard(e) && !(e instanceof net.minecraft.world.entity.monster.Guardian);
    }

    /** Guards and iron golems: the village's protectors, who give their own requests. */
    public static boolean isGiver(net.minecraft.world.entity.Entity e) {
        return isGuard(e) || e instanceof net.minecraft.world.entity.animal.IronGolem;
    }

    private static boolean isGolem(Mob giver) {
        return giver instanceof net.minecraft.world.entity.animal.IronGolem;
    }

    /** Golems ask only someone they've come to trust ({@link QuestConfig#GOLEM_QUEST_MIN_TRUST}). */
    public static boolean golemTrusts(ServerPlayer player, Mob golem) {
        return player.isCreative() || GuardTrust.score(player, golem) >= QuestConfig.GOLEM_QUEST_MIN_TRUST.get();
    }

    /** Line keys: "villagequests.guard.*" or "villagequests.golem.*". */
    private static String voice(Mob giver) {
        return isGolem(giver) ? "villagequests.golem." : "villagequests.guard.";
    }

    // ------------------------------------------------------------------ offers

    public static void tick(net.minecraft.server.MinecraftServer server, int tick) {
        if (!QuestsModule.enabled() || tick % SCAN_INTERVAL != 0) return;
        double perDay = QuestConfig.OFFER_CHANCE_PER_DAY.get();
        double chance = 1.0 - Math.pow(1.0 - Math.min(perDay, 0.9999), SCAN_INTERVAL / 24000.0);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            long now = level.getGameTime();
            for (Mob guard : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(SCAN_RADIUS), GuardQuests::isGiver)) {
                GiverData data = guard.getData(QuestRegistries.GIVER);
                if (data.hasOffer()) {
                    level.sendParticles(player, ParticleTypes.WAX_ON, false, guard.getX(), guard.getY() + guard.getBbHeight() + 0.5,
                            guard.getZ(), 2, 0.15, 0.1, 0.15, 0.0);
                    continue;
                }
                if (now < data.nextOfferTime() || level.random.nextDouble() >= chance) continue;
                tryOffer(guard, null, player);
            }
        }
    }

    /** Gives the guard a pending request; {@code forced} skips chance and limits (tests, debug). */
    @Nullable
    public static QuestDefinition tryOffer(Mob guard, @Nullable QuestDefinition forced) {
        return tryOffer(guard, forced, null);
    }

    /** {@code near}: the player this guard would ask; security requests wait until they're trusted (tier 1). */
    @Nullable
    public static QuestDefinition tryOffer(Mob guard, @Nullable QuestDefinition forced, @Nullable ServerPlayer near) {
        ServerLevel level = (ServerLevel) guard.level();
        GiverData data = guard.getData(QuestRegistries.GIVER);
        QuestDefinition def = forced;
        if (def == null && isGolem(guard) && near != null && !golemTrusts(near, guard)) return null;
        if (def == null) {
            Set<ResourceLocation> exclude = new HashSet<>();
            int open = 0;
            for (Mob other : level.getEntitiesOfClass(Mob.class, guard.getBoundingBox().inflate(Reputation.VILLAGE_RADIUS), GuardQuests::isGiver)) {
                GiverData d = other.getData(QuestRegistries.GIVER);
                if (d.hasOffer()) {
                    open++;
                    exclude.add(ResourceLocation.parse(d.offer()));
                }
            }
            if (open >= MAX_OPEN_NEARBY) return null;
            if (!data.lastQuest().isEmpty()) exclude.add(ResourceLocation.parse(data.lastQuest()));
            for (int attempt = 0; attempt < 4; attempt++) {
                def = QuestDefinitions.pickFor(isGolem(guard) ? QuestDefinition.Givers.GOLEM : QuestDefinition.Givers.GUARD, exclude, level.random);
                boolean untrusted = def != null && near != null && Security.isPlacement(def) && !Security.trustedEnough(near, guard);
                if (def == null || (!untrusted && (!def.isBuild() || Build.offerable(def, guard)))) break;
                exclude.add(def.id());
                def = null;
            }
            if (def == null) {
                guard.setData(QuestRegistries.GIVER, data.cleared(level.getGameTime() + 24000, data.lastQuest()));
                return null;
            }
        }
        int count = def.isBuild() ? Build.target(def, guard)
                : def.isErrand() ? Mth.nextInt(level.random, def.request().min(), def.request().max()) : 0;
        guard.setData(QuestRegistries.GIVER, data.withOffer(def.id().toString(), count));
        return def;
    }

    @Nullable
    private static QuestDefinition pendingOffer(ServerPlayer player, Mob guard) {
        GiverData giver = guard.getData(QuestRegistries.GIVER);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        if (!giver.hasOffer() || !quests.fromGiver(guard.getUUID()).isEmpty()) return null;
        if (quests.activeCount() >= QuestConfig.MAX_ACTIVE_QUESTS.get()) return null;
        QuestDefinition def = QuestDefinitions.get(ResourceLocation.parse(giver.offer()));
        if (def != null && Security.isPlacement(def) && !Security.trustedEnough(player, guard)) return null;
        if (isGolem(guard)) return def != null && def.givers() == QuestDefinition.Givers.GOLEM && golemTrusts(player, guard) ? def : null;
        return def != null && def.givers() == QuestDefinition.Givers.GUARD ? def : null;
    }

    // ------------------------------------------------------------------ dialogue

    public static QuestDialogue.Opening open(ServerPlayer player, Mob guard) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        Map<String, Component> labels = new HashMap<>();
        List<String> options = new ArrayList<>();

        for (ActiveQuest quest : quests.fromGiver(guard.getUUID())) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !quest.isActive()) continue;
            if (def.isBuild()) {
                Component done = completeBuild(player, guard, quest, def);
                if (done != null) return new QuestDialogue.Opening(List.of(), Map.of(), java.util.Optional.of(done));
            }
            if (def.isErrand() && QuestManager.countMatching(player, def.request()) >= quest.count) {
                option(options, labels, QuestManager.DELIVER, "guard_deliver");
                option(options, labels, QuestManager.LATER, "later");
                return new QuestDialogue.Opening(options, labels,
                        java.util.Optional.of(Component.translatable("villagequests.guard.ready", QuestManager.requestText(def, quest.count))));
            }
            option(options, labels, QuestManager.QUIT, "quit_" + Math.floorMod(guard.getUUID().hashCode() >> 3, 3));
            return new QuestDialogue.Opening(options, labels, java.util.Optional.empty());
        }
        if (pendingOffer(player, guard) != null) {
            options.add(ASK);
            labels.put(ASK, Component.translatable("villagequests.dialogue.option.guard_ask"));
            return new QuestDialogue.Opening(options, labels,
                    java.util.Optional.of(Component.translatable(voice(guard) + "greeting." + Math.floorMod(guard.getUUID().hashCode(), 3))));
        }
        return new QuestDialogue.Opening(List.of(), Map.of(), java.util.Optional.empty());
    }

    private static void option(List<String> options, Map<String, Component> labels, String choice, String labelKey) {
        String id = QuestDialogue.PREFIX + choice;
        options.add(id);
        labels.put(id, Component.translatable("villagequests.dialogue.option." + labelKey));
    }

    @Nullable
    public static QuestDialogue.Reply handle(ServerPlayer player, Mob guard, String action, List<String> base) {
        if (guard.distanceToSqr(player) > 8 * 8) return null;
        Component line;
        if (action.equals(ASK)) {
            QuestDefinition def = pendingOffer(player, guard);
            if (def == null) return null;
            Map<String, Component> labels = new HashMap<>();
            List<String> options = new ArrayList<>();
            option(options, labels, QuestManager.ACCEPT, "guard_accept");
            option(options, labels, QuestManager.DECLINE, "decline");
            return new QuestDialogue.Reply(story(def, guard, guard.getData(QuestRegistries.GIVER).count()), options, labels);
        }
        String choice = action.substring(QuestDialogue.PREFIX.length());
        line = switch (choice) {
            case QuestManager.ACCEPT -> accept(player, guard);
            case QuestManager.DECLINE -> {
                GiverData g = guard.getData(QuestRegistries.GIVER);
                guard.setData(QuestRegistries.GIVER, g.cleared(guard.level().getGameTime() + 12000, g.offer()));
                yield Component.translatable(voice(guard) + "declined");
            }
            case QuestManager.DELIVER -> deliver(player, guard);
            case QuestManager.LATER -> Component.translatable(voice(guard) + "later");
            case QuestManager.QUIT -> quit(player, guard);
            default -> null;
        };
        if (line == null) line = Component.translatable("villagequests.dialogue.hm");
        QuestDialogue.Opening next = open(player, guard);
        List<String> options = new ArrayList<>(next.options());
        options.addAll(base);
        return new QuestDialogue.Reply(line, options, next.labels());
    }

    private static Component story(QuestDefinition def, Mob guard, int count) {
        String key = def.variant(def.text().story(), guard.getUUID());
        if (def.isRescue()) return Component.translatable(key, guard.getName(), Component.literal(Rescue.buddyName(guard.getUUID(), def)));
        return def.isErrand()
                ? Component.translatable(key, guard.getName(), count, def.request().displayName())
                : Component.translatable(key, guard.getName(), count);
    }

    @Nullable
    private static Component accept(ServerPlayer player, Mob guard) {
        QuestDefinition def = pendingOffer(player, guard);
        if (def == null) return null;
        ServerLevel level = player.serverLevel();
        GiverData giver = guard.getData(QuestRegistries.GIVER);
        long now = level.getGameTime();
        // A guard's "village" is the town their nearest bell belongs to (or where they stand).
        BlockPos village = level.getPoiManager().findClosest(h -> h.is(PoiTypes.MEETING), guard.blockPosition(), 64, PoiManager.Occupancy.ANY)
                .map(bell -> dev.villagechatter.reputation.Villages.bellCluster(level, bell).get(0)).orElse(guard.blockPosition());
        dev.villagechatter.reputation.Villages.learnName(level, village);
        if (def.isRescue()) return acceptRescue(player, guard, def, village);
        int count = Math.max(1, giver.count());
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), guard.getUUID(), guard.getName().copy(), level.dimension(),
                village, guard.blockPosition(), def.isBuild() ? "build" : "errand", now, now + def.deadlineTicks(),
                ActiveQuest.State.ACTIVE, false, 0, count);
        player.getData(QuestRegistries.PLAYER_QUESTS).quests.add(quest);
        long cooldown = Math.round(QuestConfig.OFFER_COOLDOWN_DAYS.get() * 24000);
        guard.setData(QuestRegistries.GIVER, giver.cleared(now + cooldown, def.id().toString()));
        guard.playSound(SoundEvents.VILLAGER_YES, 0.8F, 0.8F);
        String key = def.variant(def.text().thanks(), guard.getUUID());
        return def.isErrand()
                ? Component.translatable(key, guard.getName(), count, def.request().displayName(), QuestText.duration(def.deadlineTicks()))
                : Component.translatable(key, guard.getName(), count, QuestText.duration(def.deadlineTicks()));
    }

    /** A golem's "one of ours is lost": a real villager (sometimes a child) waits out there; bring them home. */
    @Nullable
    private static Component acceptRescue(ServerPlayer player, Mob golem, QuestDefinition def, BlockPos village) {
        ServerLevel level = player.serverLevel();
        GiverData giver = golem.getData(QuestRegistries.GIVER);
        long now = level.getGameTime();
        TargetFinder.Target target = TargetFinder.find(level, golem.blockPosition(), def.location(), level.random);
        if (target == null) {
            golem.setData(QuestRegistries.GIVER, giver.cleared(now + 24000, def.id().toString()));
            return Component.translatable(voice(golem) + "nevermind");
        }
        ActiveQuest quest = new ActiveQuest(UUID.randomUUID(), def.id(), golem.getUUID(), golem.getName().copy(), level.dimension(),
                village, target.pos(), target.kind(), now, now + def.deadlineTicks(), ActiveQuest.State.ACTIVE, false, 0, 0);
        player.getData(QuestRegistries.PLAYER_QUESTS).quests.add(quest);
        long cooldown = Math.round(QuestConfig.OFFER_COOLDOWN_DAYS.get() * 24000);
        golem.setData(QuestRegistries.GIVER, giver.cleared(now + cooldown, def.id().toString()));
        String buddy = Rescue.buddyName(golem.getUUID(), def);
        Rescue.tip(player);
        golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 0.6F, 0.8F);
        return Component.translatable(def.variant(def.text().thanks(), golem.getUUID()), golem.getName(), buddy,
                QuestText.placePhrase(target.kind(), golem.blockPosition(), target.pos()), QuestText.roughCoords(target.pos()),
                QuestText.duration(def.deadlineTicks()));
    }

    @Nullable
    private static Component deliver(ServerPlayer player, Mob guard) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        for (ActiveQuest quest : quests.fromGiver(guard.getUUID())) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isErrand() || !quest.isActive()) continue;
            if (QuestManager.countMatching(player, def.request()) < quest.count) {
                return Component.translatable("villagequests.say.errand_short", QuestManager.requestText(def, quest.count));
            }
            QuestManager.takeMatching(player, def.request(), quest.count);
            reward(player, guard, def, quest.count);
            quests.quests.remove(quest);
            QuestManager.removeNotesFor(player, quest);
            return Component.translatable(def.variant(def.text().done(), guard.getUUID()), guard.getName());
        }
        return null;
    }

    @Nullable
    private static Component completeBuild(ServerPlayer player, Mob guard, ActiveQuest quest, QuestDefinition def) {
        if (Build.have(player.serverLevel(), quest, def) < quest.count) return null;
        if (Security.isPlacement(def)) {
            Security.reward(player, guard, def, quest.count);
            celebrate(guard);
        } else {
            reward(player, guard, def, 0);
        }
        player.getData(QuestRegistries.PLAYER_QUESTS).quests.remove(quest);
        QuestManager.removeNotesFor(player, quest);
        return Component.translatable(def.variant(def.text().done(), guard.getUUID()), guard.getName());
    }

    /** Emeralds and XP, then the real prize: this guard's trust (goodwill keyed to them) and a little village standing. */
    private static void reward(ServerPlayer player, Mob guard, QuestDefinition def, int count) {
        QuestDefinition.Rewards r = def.rewards();
        int emeralds = (int) Math.round(count * r.emeraldsPerItem());
        if (r.emeraldsMax() > 0) emeralds += Mth.nextInt(player.getRandom(), r.emeraldsMin(), r.emeraldsMax());
        if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
        int xp = r.xp() + (int) Math.round(count * r.xpPerItem());
        if (xp > 0) player.giveExperiencePoints(xp);
        addGuardGoodwill(player, guard, r.goodwillGiver() * QuestConfig.REPUTATION_MULTIPLIER.get());
        if (r.goodwillVillage() > 0) Reputation.addVillageGoodwill(player, guard, r.goodwillVillage());
        celebrate(guard);
    }

    private static void celebrate(Mob guard) {
        guard.playSound(SoundEvents.VILLAGER_CELEBRATE, 0.8F, 0.8F);
        if (guard.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, guard.getX(), guard.getEyeY() + 0.3, guard.getZ(), 6, 0.3, 0.3, 0.3, 0);
        }
    }

    /** Guards have no villager gossip to pay out into, so their goodwill just accumulates (GuardTrust reads it). */
    static void addGuardGoodwill(ServerPlayer player, Mob guard, double amount) {
        player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), amount, Double::sum);
    }

    @Nullable
    private static Component quit(ServerPlayer player, Mob guard) {
        for (ActiveQuest quest : player.getData(QuestRegistries.PLAYER_QUESTS).fromGiver(guard.getUUID())) {
            if (!quest.isActive()) continue;
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            boolean paid = def != null && Security.isPlacement(def) && Security.payPartial(player, guard, quest, def);
            if (!paid) addGuardGoodwill(player, guard, -1.0);
            QuestManager.fail(player, quest, "villagequests.fail.quit");
            return Component.translatable(paid ? "villagequests.guard.quit_paid" : voice(guard) + "quit");
        }
        return null;
    }

    /** A guard who gave you a request died: it fails, with the reason in the journal. */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!isGiver(event.getEntity()) || !(event.getEntity().level() instanceof ServerLevel level)) return;
        UUID id = event.getEntity().getUUID();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            for (ActiveQuest quest : List.copyOf(player.getData(QuestRegistries.PLAYER_QUESTS).fromGiver(id))) {
                player.sendSystemMessage(Component.translatable("villagequests.msg.giver_died", quest.giverName).withStyle(ChatFormatting.GRAY));
                QuestManager.fail(player, quest, "villagequests.fail.giver_died");
            }
        }
    }
}

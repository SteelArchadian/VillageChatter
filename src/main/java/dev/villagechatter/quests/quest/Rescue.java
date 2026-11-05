package dev.villagechatter.quests.quest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Rescue quests: "my buddy went out that way and never came back". The giver hands over a note; the journal gives
 * the rough spot (usually a pillager outpost). The buddy only appears once the quest owner is close. Talk to them
 * to free them; they follow anyone holding an emerald block. Bring them within reach of the village to finish.
 *
 * The buddy can't be hurt while this plays out: pillagers go for villagers, and an escort that dies to one stray
 * crossbow bolt isn't fun. They're an ordinary villager again once home (or if the quest ends some other way).
 */
public final class Rescue {
    private Rescue() {}

    private static final String[] NAMES = {"Bartholomew", "Wendell", "Gus", "Mortimer", "Agnes", "Hilda", "Barnaby",
            "Tobias", "Edna", "Percival", "Otto", "Mabel", "Fennick", "Rosalind", "Clement", "Dot"};
    /** How close the owner gets before the buddy (and, away from outposts, their captors) appear. */
    private static final int SPAWN_DISTANCE = 40;
    /** Follow range for the emerald block, and how close to the village counts as home. */
    private static final double FOLLOW_RANGE = Escort.DEFAULT_RANGE, HOME_RANGE = 16.0;

    /** The buddy's name, fixed per giver so the story, note and buddy agree. */
    public static String buddyName(UUID giver, QuestDefinition def) {
        return NAMES[Math.floorMod(giver.hashCode() * 17 + def.id().hashCode(), NAMES.length)];
    }

    /** The how-to, said once at accept as a gray italic hint: lost villagers follow an emerald block. */
    static void tip(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("villagequests.rescue.tip").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ------------------------------------------------------------------ ticking (once a second, per online owner)

    static void tickPlayer(ServerPlayer player, ActiveQuest quest, QuestDefinition def, PlayerQuests quests) {
        if (!quest.isActive() || player.level().dimension() != quest.dimension) return;
        ServerLevel level = player.serverLevel();
        Villager buddy = quest.buddy == null ? null : level.getEntity(quest.buddy) instanceof Villager v ? v : null;

        if (!quest.itemDropped) {
            // Not rescued yet: make sure they're there when you arrive.
            boolean near = player.blockPosition().distSqr(quest.target) <= (double) SPAWN_DISTANCE * SPAWN_DISTANCE;
            // Only once the spot is entity-ticking: one added to a section that isn't yet stays out of every lookup, and we'd spawn another.
            if (near && buddy == null && level.isPositionEntityTicking(quest.target)
                    && (quest.buddy == null || player.blockPosition().distSqr(quest.target) < 12 * 12)) {
                // One we spawned may just not be in the entity lookup yet (its chunk section loads a tick later): adopt it, don't spawn twice.
                Villager existing = level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(quest.target).inflate(16),
                        v -> v.getData(QuestRegistries.CAPTIVE).instance().equals(quest.instance)).stream().findFirst().orElse(null);
                if (existing != null) quest.buddy = existing.getUUID();
                else spawnBuddy(level, player, quest, def);
            }
            return;
        }
        if (buddy == null) return;

        // Rescued: follow the emerald block, and finish once home.
        double homeDist = Math.sqrt(buddy.blockPosition().distSqr(new BlockPos(quest.giverPos.getX(), buddy.getBlockY(), quest.giverPos.getZ())));
        if (homeDist <= HOME_RANGE) {
            complete(player, buddy, quest, def, quests);
            return;
        }
        Escort.follow(player, buddy, FOLLOW_RANGE);
    }

    private static void spawnBuddy(ServerLevel level, ServerPlayer owner, ActiveQuest quest, QuestDefinition def) {
        Villager buddy = EntityType.VILLAGER.create(level);
        if (buddy == null) return;
        BlockPos spot = TargetFinder.findSpawnSpot(level, quest.target, 8);
        // A spot in a chunk that isn't ticking yet would hide the buddy from us and we'd spawn another.
        if (spot == null || !level.isPositionEntityTicking(spot)) spot = quest.target;
        buddy.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
        buddy.setCustomName(Component.literal(buddyName(quest.giver, def)));
        // A golem's lost villager is sometimes a child ("buddy": {"baby_chance": 0.4}).
        if (level.random.nextDouble() < GsonHelper.getAsDouble(def.settings(), "baby_chance", 0.0)) buddy.setAge(-24000);
        buddy.setPersistenceRequired();
        holdCaptive(buddy, true);
        buddy.setData(QuestRegistries.CAPTIVE, new GuardianData(quest.instance, owner.getUUID()));
        level.addFreshEntity(buddy);
        quest.buddy = buddy.getUUID();

        // Out in the wilds (no outpost found), a few pillagers keep watch.
        if (!quest.kind.contains("outpost") && GsonHelper.getAsBoolean(def.settings(), "captors", true)) {
            for (int i = 0; i < 3; i++) {
                Pillager captor = EntityType.PILLAGER.create(level);
                if (captor == null) continue;
                BlockPos at = TargetFinder.findSpawnSpot(level, spot.offset(level.random.nextInt(9) - 4, 0, level.random.nextInt(9) - 4), 6);
                if (at == null) at = spot;
                captor.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
                EventHooks.finalizeMobSpawn(captor, level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
                captor.setPersistenceRequired();
                level.addFreshEntity(captor);
            }
        }
    }

    /** Captive: can't move off, can't be hurt. Freed: an ordinary villager. */
    private static void holdCaptive(Villager buddy, boolean held) {
        buddy.setInvulnerable(held);
        if (held) {
            buddy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MobEffectInstance.INFINITE_DURATION, 6, false, false));
        } else {
            buddy.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    // ------------------------------------------------------------------ talking to the buddy

    /** The owner's rescue quest this villager is the captive of, if any. */
    @Nullable
    static ActiveQuest questFor(ServerPlayer player, Villager villager) {
        if (!villager.hasData(QuestRegistries.CAPTIVE)) return null;
        GuardianData data = villager.getData(QuestRegistries.CAPTIVE);
        if (data.isNone() || !data.owner().equals(player.getUUID())) return null;
        return player.getData(QuestRegistries.PLAYER_QUESTS).byInstance(data.instance()).filter(ActiveQuest::isActive).orElse(null);
    }

    /** What the buddy says when you talk to them. Freeing them happens here. Null = not a buddy of yours. */
    @Nullable
    static Component talk(ServerPlayer player, Villager buddy) {
        ActiveQuest quest = questFor(player, buddy);
        if (quest == null) return null;
        if (quest.itemDropped) return Component.translatable("villagequests.rescue.following");
        quest.itemDropped = true;
        holdCaptive(buddy, false);
        // Still invulnerable for the walk home; see the class comment.
        buddy.setInvulnerable(true);
        buddy.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, buddy.getVoicePitch());
        if (buddy.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, buddy.getX(), buddy.getEyeY(), buddy.getZ(), 10, 0.4, 0.4, 0.4, 0);
        }
        return Component.translatable("villagequests.rescue.found", quest.giverName);
    }

    private static void complete(ServerPlayer player, Villager buddy, ActiveQuest quest, QuestDefinition def, PlayerQuests quests) {
        ServerLevel level = player.serverLevel();
        QuestDefinition.Rewards r = def.rewards();
        int emeralds = net.minecraft.util.Mth.nextInt(player.getRandom(), r.emeraldsMin(), Math.max(r.emeraldsMin(), r.emeraldsMax()));
        if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
        if (r.xp() > 0) player.giveExperiencePoints(r.xp());

        Entity giver = level.getEntity(quest.giver);
        if (giver instanceof Villager g) {
            Reputation.adjust(player, g, r.repReturned(), true);
            Reputation.addGoodwill(player, g, r.goodwillGiver(), r.goodwillVillage());
            g.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, g.getVoicePitch());
        } else if (giver instanceof net.minecraft.world.entity.Mob keeper && GuardQuests.isGiver(keeper)) {
            // A golem's (or guard's) request: their own trust, the village's shared standing with its protectors.
            double mult = QuestConfig.REPUTATION_MULTIPLIER.get();
            GuardQuests.addGuardGoodwill(player, keeper, r.goodwillGiver() * mult);
            boolean golem = keeper instanceof net.minecraft.world.entity.animal.IronGolem;
            dev.villagechatter.life.law.Law.goodDeed(player, buddy, golem ? r.goodwillGiver() * mult / 2 : 0, golem ? 0 : r.goodwillGiver() * mult / 2);
            Reputation.addVillageGoodwill(player, buddy, r.goodwillVillage());
            keeper.playSound(SoundEvents.IRON_GOLEM_REPAIR, 0.8F, 1.0F);
        } else {
            Reputation.addVillageGoodwill(player, buddy, r.goodwillVillage());
        }
        player.sendSystemMessage(Component.literal("<").append(buddy.getName().copy().withStyle(ChatFormatting.GOLD)).append("> ")
                .append(Component.translatable(def.variant(def.text().done(), quest.giver), quest.giverName, buddy.getName())));
        free(buddy);
        quests.quests.remove(quest);
        QuestManager.removeNotesFor(player, quest);
    }

    private static void free(Villager buddy) {
        holdCaptive(buddy, false);
        buddy.removeData(QuestRegistries.CAPTIVE);
        Escort.stop(buddy);
    }

    // ------------------------------------------------------------------ cleanup

    /** A captive loaded from disk whose quest is gone (lapsed, abandoned, giver died) just goes free. */
    static void onLoaded(Villager villager, @Nullable ServerPlayer owner) {
        GuardianData data = villager.getData(QuestRegistries.CAPTIVE);
        if (data.isNone() || owner == null) return;
        boolean wanted = owner.getData(QuestRegistries.PLAYER_QUESTS).byInstance(data.instance())
                .map(ActiveQuest::isActive).orElse(false);
        if (!wanted) free(villager);
    }

    /** When a rescue ends without a reunion: free the buddy if they're loaded. */
    static void release(ServerLevel level, ActiveQuest quest) {
        if (quest.buddy != null && level.getEntity(quest.buddy) instanceof Villager buddy) free(buddy);
    }

    static List<Component> storyArgs(QuestDefinition def, Villager giver) {
        return List.of(giver.getName(), Component.literal(buddyName(giver.getUUID(), def)));
    }
}

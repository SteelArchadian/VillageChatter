package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A kid's lost pet: "I lost my cat!" The cat (or dog) waits in a forest or cave nearby, appearing once the quest
 * owner gets close. Right-click it ("Come on, let's go home") and it trusts you for the trip: it's your pet until
 * it's back beside the kid, so the usual pet talk works (stay, follow, follow distance, tricks, treats, and "go home",
 * which here means back to the kid). Rewards are kid-sized: XP, goodwill with the kid and the village's golems, and
 * one odd little present (a flower, a cookie, mud...). No emeralds; kids don't have any.
 *
 * Abandoning the quest once you've found the pet means keeping it: it stays yours, there's no reward, and the kid
 * takes it as theft ({@link #KEPT_GOODWILL}).
 *
 * Afterwards a returned pet is untamed again and carries the persistent tag {@value #KID_OWNER} (the kid's UUID);
 * Village Chatter's life module handles what that means (taming it later being a crime against the kid).
 *
 * Like a rescued buddy, the pet can't be hurt while the quest plays out.
 */
public final class LostPet {
    private LostPet() {}

    /** Persistent-data key on a returned pet: the kid's UUID as a string. Read by Village Chatter's life module. */
    public static final String KID_OWNER = "villagechatter:kid_owner";

    private static final String[] CAT_NAMES = {"Whiskers", "Mittens", "Pumpkin", "Socks", "Muffin", "Noodle", "Pickles", "Smudge"};
    private static final String[] DOG_NAMES = {"Biscuit", "Rex", "Pip", "Buster", "Waffles", "Scout", "Bean", "Clover"};
    private static final int SPAWN_DISTANCE = 40;
    /** The pet counts as home once it's this close to the kid. */
    private static final double HOME_RANGE = 5.0;
    /** Goodwill the kid (and, a little, the village) loses when you keep their pet. */
    private static final double KEPT_GOODWILL = -6.0, KEPT_VILLAGE = -1.0;

    // ------------------------------------------------------------------ the pet's identity (fixed per kid and quest)

    /** The pet's entity type for this kid: cat or dog (or whatever the JSON lists). */
    public static EntityType<?> species(UUID giver, QuestDefinition def) {
        List<String> types = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(def.settings(), "pets")) types.add(e.getAsString());
        String id = types.get(Math.floorMod(giver.hashCode() * 31 + def.id().hashCode(), types.size()));
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
    }

    private static boolean isCat(EntityType<?> type) {
        return type == EntityType.CAT || type == EntityType.OCELOT;
    }

    /** "cat" / "dog" as the kid would say it. */
    public static Component speciesWord(UUID giver, QuestDefinition def) {
        return Component.translatable(isCat(species(giver, def)) ? "villagequests.pet.cat" : "villagequests.pet.dog");
    }

    public static Component petName(UUID giver, QuestDefinition def) {
        String[] names = isCat(species(giver, def)) ? CAT_NAMES : DOG_NAMES;
        return Component.literal(names[Math.floorMod(giver.hashCode() * 7 + 3, names.length)]);
    }

    // ------------------------------------------------------------------ ticking (once a second, per online owner)

    static void tickPlayer(ServerPlayer player, ActiveQuest quest, QuestDefinition def, PlayerQuests quests) {
        if (!quest.isActive() || player.level().dimension() != quest.dimension) return;
        ServerLevel level = player.serverLevel();
        Entity pet = quest.buddy == null ? null : level.getEntity(quest.buddy);

        if (!quest.itemDropped) {
            boolean near = player.blockPosition().distSqr(quest.target) <= (double) SPAWN_DISTANCE * SPAWN_DISTANCE;
            // Only once the spot is entity-ticking, so the pet can't land out of every lookup and be spawned twice.
            if (near && pet == null && level.isPositionEntityTicking(quest.target)
                    && (quest.buddy == null || player.blockPosition().distSqr(quest.target) < 12 * 12)) {
                spawnPet(level, player, quest, def);
            }
            return;
        }
        if (!(pet instanceof Mob mob)) return;
        if (level.getEntity(quest.giver) instanceof Villager kid && mob.distanceTo(kid) <= HOME_RANGE) {
            complete(player, kid, mob, quest, def, quests);
        }
    }

    private static void spawnPet(ServerLevel level, ServerPlayer owner, ActiveQuest quest, QuestDefinition def) {
        if (!(species(quest.giver, def).create(level) instanceof Mob pet)) return;
        BlockPos spot = TargetFinder.findSpawnSpot(level, quest.target, 8);
        if (spot == null || !level.isPositionEntityTicking(spot)) spot = quest.target;
        pet.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(pet, level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        pet.setCustomName(petName(quest.giver, def));
        pet.setPersistenceRequired();
        pet.setInvulnerable(true);
        pet.setData(QuestRegistries.CAPTIVE, new GuardianData(quest.instance, owner.getUUID()));
        addGoal(pet);
        level.addFreshEntity(pet);
        quest.buddy = pet.getUUID();
    }

    // ------------------------------------------------------------------ finding it

    /**
     * Right-clicking the lost pet: "Come on, let's go home." It trusts you for the trip (tamed to you, without the
     * taming items or the theft check). Once found, clicks fall through to the usual pet talk and treats.
     */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Mob pet)
                || pet instanceof Villager || !pet.hasData(QuestRegistries.CAPTIVE)) return;
        ActiveQuest quest = questFor(player, pet);
        if (quest != null && quest.itemDropped && pet instanceof TamableAnimal t && t.isOwnedBy(player)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (quest == null) {
            player.displayClientMessage(Component.translatable("villagequests.pet.not_yours", pet.getName()), true);
            return;
        }
        quest.itemDropped = true;
        if (pet instanceof TamableAnimal t) {
            t.tame(player);
            t.setOrderedToSit(false);
            t.setInSittingPose(false);
        }
        pet.playSound(isCat(pet.getType()) ? SoundEvents.CAT_PURREOW : SoundEvents.WOLF_AMBIENT, 1.0F, 1.0F);
        if (pet.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, pet.getX(), pet.getEyeY() + 0.3, pet.getZ(), 4, 0.3, 0.2, 0.3, 0);
        }
        player.displayClientMessage(Component.translatable("villagequests.pet.come_home", pet.getName(), quest.giverName), true);
    }

    /**
     * Where "go home" sends a found lost pet: the floor under its kid (or the kid's house when the kid isn't loaded).
     * Null for any other pet. Used by Village Chatter's pet talk.
     */
    @Nullable
    public static GlobalPos homeFor(Entity pet) {
        if (!pet.hasData(QuestRegistries.CAPTIVE) || !(pet.level() instanceof ServerLevel level)) return null;
        GuardianData data = pet.getData(QuestRegistries.CAPTIVE);
        if (data.isNone()) return null;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(data.owner());
        ActiveQuest quest = owner == null ? null : questFor(owner, pet);
        if (quest == null || !quest.itemDropped) return null;
        ServerLevel home = level.getServer().getLevel(quest.dimension);
        BlockPos at = home != null && home.getEntity(quest.giver) instanceof Villager kid ? kid.blockPosition() : quest.giverPos;
        return GlobalPos.of(quest.dimension, at.below());
    }

    @Nullable
    static ActiveQuest questFor(ServerPlayer player, Entity pet) {
        if (!pet.hasData(QuestRegistries.CAPTIVE)) return null;
        GuardianData data = pet.getData(QuestRegistries.CAPTIVE);
        if (data.isNone() || !data.owner().equals(player.getUUID())) return null;
        return player.getData(QuestRegistries.PLAYER_QUESTS).byInstance(data.instance()).filter(ActiveQuest::isActive).orElse(null);
    }

    // ------------------------------------------------------------------ home

    private static void complete(ServerPlayer player, Villager kid, Mob pet, ActiveQuest quest, QuestDefinition def, PlayerQuests quests) {
        ServerLevel level = player.serverLevel();
        QuestDefinition.Rewards r = def.rewards();
        if (r.xp() > 0) player.giveExperiencePoints(r.xp());
        Reputation.addGoodwill(player, kid, r.goodwillGiver(), r.goodwillVillage());
        // The golems like a happy kid: the village's shared golem standing.
        double golems = GsonHelper.getAsDouble(def.settings(), "golem_goodwill", 2.0) * QuestConfig.REPUTATION_MULTIPLIER.get();
        dev.villagechatter.life.law.Law.goodDeed(player, kid, golems, 0);
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, new AABB(kid.blockPosition()).inflate(Reputation.VILLAGE_RADIUS))) {
            level.sendParticles(ParticleTypes.HEART, golem.getX(), golem.getEyeY() + 0.4, golem.getZ(), 2, 0.3, 0.2, 0.3, 0);
        }
        ItemStack present = rollPresent(def, level);
        if (!present.isEmpty()) QuestManager.give(player, present);

        QuestText.say(player, kid.getName(), Component.translatable(def.variant(def.text().done(), quest.giver),
                kid.getName(), pet.getName(), present.isEmpty() ? Component.empty() : present.getHoverName()));
        kid.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, kid.getVoicePitch());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, kid.getX(), kid.getEyeY(), kid.getZ(), 8, 0.4, 0.4, 0.4, 0);

        free(pet);
        if (pet instanceof TamableAnimal t) {
            // The kid's again: no longer yours, no longer waiting where "go home" put it.
            dev.villagechatter.server.PetTalk.forget(t);
            t.setOrderedToSit(false);
            t.setInSittingPose(false);
            t.setData(dev.villagechatter.ChatterAttachments.AT_HOME, false);
            t.setTame(false, true);
            t.setOwnerUUID(null);
        }
        pet.getPersistentData().putString(KID_OWNER, kid.getUUID().toString());
        quests.quests.remove(quest);
        QuestManager.removeNotesFor(player, quest);
    }

    /** One present from the JSON pool: {"item": id} or {"tag": id}, with optional "min"/"max" counts. */
    static ItemStack rollPresent(QuestDefinition def, ServerLevel level) {
        var pool = GsonHelper.getAsJsonArray(def.settings(), "presents", new com.google.gson.JsonArray());
        if (pool.isEmpty()) return ItemStack.EMPTY;
        JsonObject pick = pool.get(level.random.nextInt(pool.size())).getAsJsonObject();
        int count = Mth.nextInt(level.random, GsonHelper.getAsInt(pick, "min", 1), Math.max(GsonHelper.getAsInt(pick, "min", 1), GsonHelper.getAsInt(pick, "max", 1)));
        Item item = null;
        if (pick.has("tag")) {
            String tag = GsonHelper.getAsString(pick, "tag").replace("#", "");
            item = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag)))
                    .flatMap(set -> set.getRandomElement(level.random)).map(h -> h.value()).orElse(null);
        } else if (pick.has("item")) {
            item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(GsonHelper.getAsString(pick, "item")));
        }
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    // ------------------------------------------------------------------ cleanup

    /** Back to being an ordinary animal. */
    private static void free(Mob pet) {
        pet.removeData(QuestRegistries.CAPTIVE);
        pet.setInvulnerable(false);
        pet.getNavigation().stop();
    }

    /**
     * The quest ended without a reunion. Not found yet: the pet, if loaded, is just a stray now. Found: it's still
     * tamed to you, so you've kept it, and the kid knows.
     */
    static void release(ServerPlayer player, ActiveQuest quest) {
        ServerLevel level = player.server.getLevel(quest.dimension);
        if (level != null && quest.buddy != null && level.getEntity(quest.buddy) instanceof Mob pet
                && pet.hasData(QuestRegistries.CAPTIVE)) free(pet);
        if (quest.itemDropped) kept(player, quest, level);
    }

    /** "I think I'll keep you for myself": the quest ends and the pet stays yours (see {@link #kept}). */
    public static boolean keep(ServerPlayer player, Entity pet) {
        ActiveQuest quest = questFor(player, pet);
        if (quest == null || !quest.itemDropped) return false;
        QuestManager.fail(player, quest, "villagequests.fail.kept_pet");
        return true;
    }

    private static void kept(ServerPlayer player, ActiveQuest quest, @Nullable ServerLevel level) {
        if (level != null && level.getEntity(quest.giver) instanceof Villager kid) {
            Reputation.addGoodwill(player, kid, KEPT_GOODWILL, KEPT_VILLAGE); // (applies the multiplier itself)
            kid.playSound(SoundEvents.VILLAGER_NO, 1.0F, kid.getVoicePitch());
        } else {
            player.getData(QuestRegistries.GOODWILL).points.merge(quest.giver, KEPT_GOODWILL * QuestConfig.REPUTATION_MULTIPLIER.get(), Double::sum);
        }
        player.displayClientMessage(Component.translatable("villagequests.pet.kept", quest.giverName).withStyle(ChatFormatting.RED), false);
    }

    /** Goals aren't saved: re-add the follow goal to lost pets loaded from disk, or free them if their quest is gone. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob pet) || pet instanceof Villager
                || !pet.hasData(QuestRegistries.CAPTIVE)) return;
        GuardianData data = pet.getData(QuestRegistries.CAPTIVE);
        ServerPlayer owner = event.getLevel().getServer().getPlayerList().getPlayer(data.owner());
        if (owner != null && questFor(owner, pet) == null) {
            free(pet);
            return;
        }
        if (event.loadedFromDisk()) addGoal(pet);
    }

    private static void addGoal(Mob pet) {
        if (pet instanceof PathfinderMob mob) pet.goalSelector.addGoal(0, new FollowFinder(mob));
    }

    /**
     * Top-priority goal while the pet is lost: stay put and watch for whoever comes looking (so a skittish cat doesn't
     * bolt). Takes MOVE and LOOK so wandering, fleeing and begging goals wait. Once found it's tamed to the finder and
     * its own follow and sit goals take over.
     */
    static final class FollowFinder extends Goal {
        private final PathfinderMob pet;
        @Nullable
        private ServerPlayer leader;

        FollowFinder(PathfinderMob pet) {
            this.pet = pet;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            if (!pet.hasData(QuestRegistries.CAPTIVE) || !(pet.level() instanceof ServerLevel level)) return false;
            GuardianData data = pet.getData(QuestRegistries.CAPTIVE);
            if (data.isNone()) return false;
            leader = level.getServer().getPlayerList().getPlayer(data.owner());
            if (leader == null || leader.level() != pet.level()) return false;
            ActiveQuest quest = questFor(leader, pet);
            return quest != null && !quest.itemDropped;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            pet.getNavigation().stop();
            leader = null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (leader == null) return;
            // Still lost: sit tight, look at whoever's near.
            pet.getNavigation().stop();
            if (pet.distanceTo(leader) < 12) pet.getLookControl().setLookAt(leader, 30F, 30F);
        }
    }

    static List<Component> storyArgs(QuestDefinition def, Villager kid) {
        return List.of(kid.getName(), speciesWord(kid.getUUID(), def), petName(kid.getUUID(), def));
    }

    /** "Bring Whiskers home": for the journal while the pet is following. */
    static Component followingStatus(ActiveQuest quest, QuestDefinition def) {
        return Component.translatable("villagequests.journal.status.pet_following", petName(quest.giver, def), quest.giverName)
                .withStyle(ChatFormatting.DARK_GREEN);
    }
}

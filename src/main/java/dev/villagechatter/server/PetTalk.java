package dev.villagechatter.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.network.ChatterNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Talking to your dogs, cats and parrots: stay / follow, follow distance, tricks, and "go home". */
public final class PetTalk {
    private PetTalk() {}

    record Reply(String topic, List<String> options) {}

    /** Commands that end the conversation on the spot, so the pet can actually go and do it. */
    public static final Set<String> ENDS_CONVERSATION = Set.of("follow", "stay", "adventure", "comeback");

    private static final int GO_HOME_DISTANCE = 32;
    private static final int GO_HOME_MAX_TICKS = 20 * 30;

    // ------------------------------------------------------------------ who counts

    static boolean isPet(Entity entity) {
        return entity instanceof Wolf || entity instanceof Cat || entity instanceof Parrot || isDoggyTalentsDog(entity);
    }

    /** Doggy Talents Next dogs: tamable, but with their own follow logic and right-click handling. */
    static boolean isDoggyTalentsDog(Entity entity) {
        return entity instanceof TamableAnimal
                && "doggytalents:dog".equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    /** Dogs from any mod count as dogs (lines, sounds, tricks). */
    static boolean isDog(Entity entity) {
        return entity instanceof Wolf || isDoggyTalentsDog(entity);
    }

    /** Items the pet itself reacts to (feeding, healing, breeding, collar dye): those clicks stay with the game. */
    static boolean usesItem(TamableAnimal pet, ItemStack held) {
        if (held.isEmpty()) return false;
        // Doggy Talents' own items (treats, collars, beds, the whistle...) and its food all do their thing.
        if (isDoggyTalentsDog(pet) && ("doggytalents".equals(BuiltInRegistries.ITEM.getKey(held.getItem()).getNamespace())
                || held.has(net.minecraft.core.component.DataComponents.FOOD))) return true;
        return pet.isFood(held) || held.getItem() instanceof DyeItem || held.is(Items.BONE);
    }

    /** Whether our follow goal replaced the pet's own (follow distance and walking home rely on it). */
    private static boolean usesOurFollowGoal(TamableAnimal pet) {
        for (WrappedGoal wrapped : pet.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof PetFollowGoal) return true;
        }
        return false;
    }

    static boolean enabledFor(Entity pet) {
        if (pet instanceof Wolf) return ChatterConfig.TALK_TO_WOLVES.get();
        if (pet instanceof Cat) return ChatterConfig.TALK_TO_CATS.get();
        if (pet instanceof Parrot) return ChatterConfig.TALK_TO_PARROTS.get();
        if (isDoggyTalentsDog(pet)) return ChatterConfig.TALK_TO_DOGGY_TALENTS.get();
        return false;
    }

    /**
     * A pet waiting at home stays there until you tell it otherwise. Getting hurt stands vanilla pets up, and they'd
     * wander off after you the next time you came by (or respawned at the bed), so it sits right back down.
     */
    public static void onHurt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof TamableAnimal pet) || pet.level().isClientSide() || !pet.getData(ChatterAttachments.AT_HOME)) return;
        if (pet.getServer() == null) return;
        pet.getServer().execute(() -> {
            if (pet.isAlive() && pet.getData(ChatterAttachments.AT_HOME) && !isSitting(pet)) sit(pet, true);
        });
    }

    // ------------------------------------------------------------------ menu

    static int flags(TamableAnimal pet) {
        // Stood up (e.g. sneak-click) since being sent home: no longer "waiting at home".
        syncSit(pet);
        if (!isSitting(pet) && pet.getData(ChatterAttachments.AT_HOME)) pet.setData(ChatterAttachments.AT_HOME, false);
        if (DoggyTalents.isInjured(pet)) return ChatterNetwork.FLAG_HURT;
        if (isGoingHome(pet)) return ChatterNetwork.FLAG_LEAVING;
        if (isSitting(pet) && pet.getData(ChatterAttachments.AT_HOME)) return ChatterNetwork.FLAG_HOME;
        return DoggyTalents.isHungry(pet) ? ChatterNetwork.FLAG_HUNGRY : 0;
    }

    static List<String> options(TamableAnimal pet) {
        if (DoggyTalents.isInjured(pet)) return List.of("comfort", "goodbye"); // it can't get up yet
        if (isGoingHome(pet)) return List.of("comeback", "goodbye");
        if (isSitting(pet) && pet.getData(ChatterAttachments.AT_HOME)) return List.of("adventure", "goodbye");
        List<String> out = new ArrayList<>();
        out.add(isSitting(pet) ? "follow" : "stay");
        if (usesOurFollowGoal(pet)) out.add("distance");
        if (isDoggyTalentsDog(pet)) out.add("attitude");
        out.add("trick");
        out.add("gohome");
        // A kid's lost pet you're bringing back: "I think I'll keep you for myself."
        if (dev.villagechatter.quests.quest.LostPet.homeFor(pet) != null) out.add("keep");
        out.add("goodbye");
        return out;
    }

    static Reply handle(ServerPlayer player, TamableAnimal pet, String action) {
        switch (action) {
            case "stay" -> {
                sit(pet, true);
                pet.setData(ChatterAttachments.AT_HOME, false);
            }
            case "follow", "adventure" -> {
                sit(pet, false);
                pet.setData(ChatterAttachments.AT_HOME, false);
            }
            case "distance" -> {
                return new Reply("reply.distance", List.of("dist_close", "dist_normal", "dist_loose", "back"));
            }
            case "dist_close" -> pet.setData(ChatterAttachments.FOLLOW_MODE, 0);
            case "dist_normal" -> pet.setData(ChatterAttachments.FOLLOW_MODE, 1);
            case "dist_loose" -> pet.setData(ChatterAttachments.FOLLOW_MODE, 2);
            case "back" -> {}
            case "comfort" -> {}
            case "attitude" -> {
                return new Reply("reply.attitude", List.of("mode_docile", "mode_aggressive", "back"));
            }
            case "mode_docile" -> DoggyTalents.setMode(pet, "DOCILE");
            case "mode_aggressive" -> DoggyTalents.setMode(pet, "AGGRESIVE"); // (sic, Doggy Talents' spelling)
            case "trick" -> startTrick(pet);
            case "gohome" -> {
                // A Doggy Talents dog goes to its own dog bed if it has one in this world, otherwise to your bed.
                // A kid's lost pet you're bringing back goes to the kid instead.
                net.minecraft.core.GlobalPos kid = dev.villagechatter.quests.quest.LostPet.homeFor(pet);
                BlockPos home = kid != null ? kid.pos() : DoggyTalents.bed(pet);
                ResourceKey<Level> homeDimension = kid != null ? kid.dimension() : pet.level().dimension();
                if (home == null && Redomestication.bed(pet) instanceof Redomestication.Bed bed) {
                    home = bed.pos();
                    homeDimension = bed.dimension();
                }
                if (home == null) {
                    home = player.getRespawnPosition();
                    homeDimension = player.getRespawnDimension();
                }
                if (home == null) return new Reply("reply.nohome", options(pet));
                sit(pet, false);
                // Pets whose own follow logic we can't pause would get pulled back, so they head off quickly.
                TRIPS.put(pet.getUUID(), new HomeTrip(player.getUUID(), pet.level().dimension(),
                        homeDimension, home, usesOurFollowGoal(pet) ? GO_HOME_MAX_TICKS : 60));
                return new Reply("reply.gohome", List.of("goodbye"));
            }
            case "keep" -> {
                if (!dev.villagechatter.quests.quest.LostPet.keep(player, pet)) return null;
            }
            case "comeback" -> {
                TRIPS.remove(pet.getUUID());
                sit(pet, false);
            }
            default -> {
                return null;
            }
        }
        return new Reply("reply." + action, options(pet));
    }

    /** What you see is what counts: a pet in its sitting pose is sitting, whatever its flags say. */
    static boolean isSitting(TamableAnimal pet) {
        return pet.isInSittingPose() || pet.isOrderedToSit() || DoggyTalents.restingOrForced(pet);
    }

    /**
     * Keep the "ordered to sit" flag and the sitting pose in agreement. If they drift apart (cat behaviours, other
     * mods) a pet can end up following its owner while still in its sitting pose, sliding along on its bottom.
     */
    static void syncSit(TamableAnimal pet) {
        if (isDoingTrick(pet)) return;
        boolean sitting = isSitting(pet);
        if (pet.isOrderedToSit() != sitting) pet.setOrderedToSit(sitting);
        if (pet.isInSittingPose() != sitting) pet.setInSittingPose(sitting);
    }

    private static void sit(TamableAnimal pet, boolean sitting) {
        if (!sitting) DoggyTalents.release(pet); // its own "force sit" and resting states would keep it down
        Redomestication.command(pet, sitting ? "SIT" : "FOLLOW"); // first: it locks the sit state to its command
        pet.setOrderedToSit(sitting);
        pet.setInSittingPose(sitting);
        pet.setJumping(false);
        pet.getNavigation().stop();
        if (sitting) pet.setTarget(null);
    }

    // ------------------------------------------------------------------ follow distance

    /** Swap vanilla's follow goal for ours so follow distance and "go home" work. */
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof TamableAnimal pet) || !isPet(pet)) return;
        for (WrappedGoal wrapped : List.copyOf(pet.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof FollowOwnerGoal) {
                int priority = wrapped.getPriority();
                pet.goalSelector.removeGoal(wrapped.getGoal());
                pet.goalSelector.addGoal(priority, new PetFollowGoal(pet, 1.0));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ go home

    private static final class HomeTrip {
        final UUID owner;
        final ResourceKey<Level> petDimension;
        final ResourceKey<Level> homeDimension;
        final BlockPos home;
        final int maxTicks;
        int ticks;

        HomeTrip(UUID owner, ResourceKey<Level> petDimension, ResourceKey<Level> homeDimension, BlockPos home, int maxTicks) {
            this.owner = owner;
            this.petDimension = petDimension;
            this.homeDimension = homeDimension;
            this.home = home;
            this.maxTicks = maxTicks;
        }
    }

    /** Pet uuid -> trip. The pet walks away from you, then pops home once out of sight. */
    private static final Map<UUID, HomeTrip> TRIPS = new HashMap<>();

    /** Drops any "go home" trip in progress (the pet changed hands). */
    public static void forget(TamableAnimal pet) {
        TRIPS.remove(pet.getUUID());
    }

    static boolean isGoingHome(Entity pet) {
        return TRIPS.containsKey(pet.getUUID());
    }

    static void tick(MinecraftServer server) {
        tickTricks();
        if (TRIPS.isEmpty()) return;
        Iterator<Map.Entry<UUID, HomeTrip>> it = TRIPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, HomeTrip> entry = it.next();
            HomeTrip trip = entry.getValue();
            ServerLevel level = server.getLevel(trip.petDimension);
            Entity found = level == null ? null : level.getEntity(entry.getKey());
            if (!(found instanceof TamableAnimal pet) || !pet.isAlive()) {
                it.remove();
                continue;
            }
            trip.ticks++;
            ServerPlayer owner = server.getPlayerList().getPlayer(trip.owner);
            double distance = owner == null || owner.level() != pet.level() ? Double.MAX_VALUE : pet.distanceTo(owner);
            if (distance >= GO_HOME_DISTANCE || trip.ticks > trip.maxTicks) {
                it.remove();
                arriveHome(server, pet, trip);
            } else if (trip.ticks % 20 == 1) {
                Vec3 away = pet.position().subtract(owner.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = Vec3.directionFromRotation(0, pet.getYRot());
                Vec3 target = pet.position().add(away.normalize().scale(12));
                pet.getNavigation().moveTo(target.x, target.y, target.z, 1.25);
            }
        }
    }

    private static void arriveHome(MinecraftServer server, TamableAnimal pet, HomeTrip trip) {
        ServerLevel dest = server.getLevel(trip.homeDimension);
        if (dest == null) dest = server.overworld();
        dest.getChunk(trip.home); // make sure it's loaded
        Vec3 spot = standUpSpot(dest, trip.home, pet);
        UUID id = pet.getUUID();
        pet.getNavigation().stop();
        Redomestication.command(pet, "SIT"); // before it leaves: a "following" pet that unloads goes to a Wayward Lantern
        pet.teleportTo(dest, spot.x, spot.y, spot.z, Set.of(), pet.getYRot(), 0);
        if (dest.getEntity(id) instanceof TamableAnimal arrived) {
            sit(arrived, true);
            arrived.setData(ChatterAttachments.AT_HOME, true);
        }
    }

    private static Vec3 standUpSpot(ServerLevel level, BlockPos bed, Entity pet) {
        BlockState state = level.getBlockState(bed);
        if (state.getBlock() instanceof BedBlock) {
            return BedBlock.findStandUpPosition(pet.getType(), level, bed, state.getValue(BedBlock.FACING), 0F)
                    .orElse(Vec3.atBottomCenterOf(bed.above()));
        }
        return Vec3.atBottomCenterOf(bed.above());
    }

    // ------------------------------------------------------------------ tricks

    /**
     * Snappy tricks a vanilla model can show (spins, a hop-spin, the wet-dog shake), and for Doggy Talents dogs their
     * own animations (backflip, roll over, howl, play bow...). All of them end on the pet's feet.
     */
    private enum TrickKind { SPIN, DOUBLE_SPIN, HOP_SPIN, SHAKE, ANIMATION }

    private static final String[] DOGGY_TALENTS_TRICKS = {"BACKFLIP", "STOP_DROP_ROLL", "HOWL", "PLAY_WITH_MEH", "GREET"};

    private static final class Trick {
        final TamableAnimal pet;
        TrickKind kind;
        final String animation;
        final boolean wasSitting;
        int ticks;

        Trick(TamableAnimal pet, TrickKind kind, String animation, boolean wasSitting) {
            this.pet = pet;
            this.kind = kind;
            this.animation = animation;
            this.wasSitting = wasSitting;
        }
    }

    private static final List<Trick> TRICKS = new ArrayList<>();

    static boolean isDoingTrick(Entity pet) {
        for (Trick t : TRICKS) if (t.pet == pet) return true;
        return false;
    }

    private static void startTrick(TamableAnimal pet) {
        if (isDoingTrick(pet)) return;
        var random = pet.getRandom();
        TrickKind kind;
        String animation = null;
        if (isDoggyTalentsDog(pet) && random.nextFloat() < 0.7F) {
            kind = TrickKind.ANIMATION;
            animation = DOGGY_TALENTS_TRICKS[random.nextInt(DOGGY_TALENTS_TRICKS.length)];
        } else {
            TrickKind[] choices = pet instanceof Wolf ? new TrickKind[]{TrickKind.SPIN, TrickKind.DOUBLE_SPIN, TrickKind.HOP_SPIN, TrickKind.SHAKE}
                    : pet instanceof Parrot ? new TrickKind[]{TrickKind.SPIN, TrickKind.HOP_SPIN}
                    : new TrickKind[]{TrickKind.SPIN, TrickKind.DOUBLE_SPIN, TrickKind.HOP_SPIN};
            kind = choices[random.nextInt(choices.length)];
        }
        boolean wasSitting = isSitting(pet);
        stand(pet);
        TRICKS.add(new Trick(pet, kind, animation, wasSitting));
    }

    private static void stand(TamableAnimal pet) {
        DoggyTalents.release(pet);
        Redomestication.command(pet, "FOLLOW");
        pet.setOrderedToSit(false);
        pet.setInSittingPose(false);
        if (pet instanceof Cat cat) cat.setLying(false);
    }

    private static void tickTricks() {
        Iterator<Trick> it = TRICKS.iterator();
        while (it.hasNext()) {
            Trick t = it.next();
            TamableAnimal pet = t.pet;
            if (!pet.isAlive()) {
                it.remove();
                continue;
            }
            if (t.ticks == 0 && t.kind == TrickKind.ANIMATION && !DoggyTalents.animate(pet, t.animation)) {
                t.kind = TrickKind.DOUBLE_SPIN; // couldn't play it: a spin instead
            }
            int duration = switch (t.kind) {
                case SPIN -> 12;
                case DOUBLE_SPIN -> 22;
                case HOP_SPIN -> 16;
                case SHAKE -> 40;
                case ANIMATION -> 60;
            };
            pet.setInSittingPose(false);
            pet.getNavigation().stop();
            switch (t.kind) {
                case SPIN -> turn(pet, 30F);                      // one quick full turn
                case DOUBLE_SPIN -> { if (t.ticks < 20) turn(pet, 36F); } // two
                case HOP_SPIN -> {
                    if (t.ticks == 0 && pet.onGround()) hop(pet);
                    if (t.ticks < 12) turn(pet, 30F);
                }
                case SHAKE -> { if (t.ticks == 0) pet.level().broadcastEntityEvent(pet, (byte) 8); }
                case ANIMATION -> {}
            }
            if (++t.ticks >= duration) {
                it.remove();
                if (pet.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.HEART, pet.getX(), pet.getY() + pet.getBbHeight() + 0.3, pet.getZ(),
                            3, 0.3, 0.2, 0.3, 0);
                }
                // Back the way it was: sitting if it was sitting, otherwise on its feet and free to follow.
                if (t.wasSitting) sit(pet, true);
                else stand(pet);
            }
        }
    }

    /**
     * Redomestication (and Domestication Innovation-style mods): pets carry a follow/sit/wander command that the sit
     * state is locked to, and its Wayward Lantern pulls in any "following" pet whose chunk unloads. A pet told to
     * stay, or sent home, gets the SIT command so it stays put and the lantern leaves it alone.
     */
    static final class Redomestication {
        private Redomestication() {}

        record Bed(BlockPos pos, ResourceKey<Level> dimension) {}

        static void command(TamableAnimal pet, String command) {
            try {
                Class<?> commandable = Class.forName("com.evandev.redomesticate.api.ICommandableMob");
                if (!commandable.isInstance(pet)) return;
                Class<?> commands = Class.forName("com.evandev.redomesticate.api.PetCommand");
                commandable.getMethod("redomesticate$setPetCommand", commands).invoke(pet, DoggyTalents.enumValue(commands, command));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }

        /** The pet bed it was assigned, if any. */
        static Bed bed(TamableAnimal pet) {
            try {
                Class<?> utils = Class.forName("com.evandev.redomesticate.util.TameableUtils");
                Object pos = utils.getMethod("getPetBedPos", net.minecraft.world.entity.LivingEntity.class).invoke(null, pet);
                if (!(pos instanceof BlockPos bedPos)) return null;
                Object dim = utils.getMethod("getPetBedDimension", net.minecraft.world.entity.LivingEntity.class).invoke(null, pet);
                ResourceKey<Level> dimension = pet.level().dimension();
                if (dim instanceof String name && !name.isEmpty()) {
                    var id = net.minecraft.resources.ResourceLocation.tryParse(name);
                    if (id != null) dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
                }
                return new Bed(bedPos, dimension);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }
    }

    /** Doggy Talents Next, reached by reflection so this mod works the same without it installed. */
    static final class DoggyTalents {
        private DoggyTalents() {}

        /** Its "force sit" (sitting on a spot) and resting/lying states, which hold a dog down besides vanilla's sit. */
        static boolean restingOrForced(TamableAnimal pet) {
            if (!isDoggyTalentsDog(pet)) return false;
            return call(pet, "forceSit") == Boolean.TRUE || call(pet, "isDogResting") == Boolean.TRUE;
        }

        /** Clears those states so "Come on, let's go" actually gets it up. */
        static void release(TamableAnimal pet) {
            if (!isDoggyTalentsDog(pet)) return;
            try {
                pet.getClass().getMethod("setForceSit", boolean.class).invoke(pet, false);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            try {
                Class<?> resting = Class.forName("doggytalents.common.entity.Dog$RestingState");
                Object none = enumValue(resting, "NONE");
                pet.getClass().getMethod("setDogRestingState", resting).invoke(pet, none);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        /** Knocked out ("incapacitated"): it lies there until it recovers, and can't follow or do tricks. */
        static boolean isInjured(TamableAnimal pet) {
            return isDoggyTalentsDog(pet) && call(pet, "getMode") instanceof Enum<?> mode && mode.name().equals("INJURED");
        }

        static boolean isHungry(TamableAnimal pet) {
            return isDoggyTalentsDog(pet) && call(pet, "isLowHunger") == Boolean.TRUE;
        }

        /** Its dog bed in the dimension it's in, if it has one. */
        static BlockPos bed(TamableAnimal pet) {
            if (!isDoggyTalentsDog(pet)) return null;
            return call(pet, "getBedPos") instanceof java.util.Optional<?> bed && bed.orElse(null) instanceof BlockPos pos
                    ? pos : null;
        }

        /** DOCILE (stays out of fights) or AGGRESIVE (goes after monsters). Leaves an injured dog alone. */
        static void setMode(TamableAnimal pet, String mode) {
            if (!isDoggyTalentsDog(pet) || isInjured(pet)) return;
            try {
                Class<?> modes = Class.forName("doggytalents.api.feature.DogMode");
                pet.getClass().getMethod("setMode", modes).invoke(pet, enumValue(modes, mode));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        /** Plays one of its own animations (synced to everyone watching). @return false if that isn't possible. */
        static boolean animate(TamableAnimal pet, String name) {
            if (!isDoggyTalentsDog(pet) || name == null) return false;
            try {
                Class<?> anim = Class.forName("doggytalents.api.anim.DogAnimation");
                pet.getClass().getMethod("setAnim", anim).invoke(pet, enumValue(anim, name));
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        private static Object call(Object target, String method) {
            try {
                return target.getClass().getMethod(method).invoke(target);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static Object enumValue(Class<?> type, String name) {
            return Enum.valueOf((Class) type, name);
        }
    }

    private static void hop(TamableAnimal pet) {
        pet.setDeltaMovement(pet.getDeltaMovement().add(0, 0.42, 0));
        pet.hasImpulse = true;
    }

    private static void turn(TamableAnimal pet, float degrees) {
        float yaw = pet.getYRot() + degrees;
        pet.setYRot(yaw);
        pet.setYBodyRot(yaw);
        pet.setYHeadRot(yaw);
    }
}

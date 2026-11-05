package dev.villagechatter.life.law;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.LivingVillage;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.quest.Reputation;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Hiring a guard (LAW_AND_ORDER.md section 8). A guard who trusts you, from a village that likes you and can spare
 * them, will come along for a fee. They follow, wait or go home like a pet, fight beside you, and keep their own
 * opinion of you (no longer part of the village's).
 *  - Any crime in a village while they're with you: they vouch for you and handle it themselves. Talk them into
 *    letting it slide (40%) and nobody else comes after you.
 *  - Lose too much of their respect: they say it isn't working out and head home. You get one try to talk them round.
 *  - A killing they see: unforgivable. They leave for good, and everything they carried for you is gone.
 */
public final class Companion {
    private Companion() {}

    private static final String HIRED_BY = "villagechatter:hired_by", HOME = "villagechatter:home", HOME_DIM = "villagechatter:home_dim",
            MODE = "villagechatter:hired_mode", LEAVING = "villagechatter:leaving", NEVER = "villagechatter:never";
    /** Standing needed with the guard, and with the village. */
    private static final double HIRE_GUARD = 40, HIRE_VILLAGE = 10;
    /** Below this they quit. */
    private static final double QUIT = 0;
    private static final double PLEAD = 0.20, EXCUSE = 0.40;
    /** How close they keep while following: close, normal, loose. */
    private static final double[] DISTANCE = {3, 5, 9};
    private static final String DIST = "villagechatter:hired_dist";
    /** First visit to a village with a hired guard at your side: the village's opinion starts this much higher. */
    private static final double ADVOCACY = 8;
    /** At least one guard per this many residents (kids too) must stay behind. */
    private static final int RESIDENTS_PER_GUARD = 6;

    /** Hired guards currently loaded (found as they load, or when hired). */
    private static final java.util.Set<Mob> HIRED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob m && isHired(m)) HIRED.add(m);
    }

    public static boolean isHiredBy(LivingEntity guard, ServerPlayer player) {
        CompoundTag data = guard.getPersistentData();
        return data.hasUUID(HIRED_BY) && data.getUUID(HIRED_BY).equals(player.getUUID());
    }

    /** Someone hired and following this player right now. */
    static boolean hasFollower(ServerPlayer player) {
        for (Mob m : HIRED) {
            if (isHiredBy(m, player) && m.getPersistentData().getString(MODE).equals("follow") && m.level() == player.level()
                    && m.distanceTo(player) <= Witnesses.RANGE) return true;
        }
        return false;
    }

    public static boolean isHired(LivingEntity guard) {
        return guard.getPersistentData().hasUUID(HIRED_BY);
    }

    private static int fee(Mob guard) {
        return 15 + Math.floorMod(guard.getUUID().hashCode(), 11);
    }

    // ------------------------------------------------------------------ dialogue

    /** Extra choices in a guard's dialogue: "Come with me" when they'd go, or the companion commands when hired. */
    public static List<String> options(ServerPlayer player, Mob guard) {
        if (!Law.enabled() || !GuardTrust.isGuard(guard)) return List.of();
        if (isHiredBy(guard, player)) {
            // At your bed (or waiting): they only come along again when you say so.
            String mode = guard.getPersistentData().getString(MODE);
            if (!mode.equals("follow")) return List.of("comp_follow", DialogueAction.INTERACT);
            return List.of("comp_stay", "comp_distance", "comp_home", DialogueAction.INTERACT);
        }
        return canHire(player, guard) ? List.of("comp_hire") : List.of();
    }

    public static Map<String, Component> labels(ServerPlayer player, Mob guard) {
        return Map.of("comp_hire", Component.translatable("villagechatter.option.comp_hire." + player.getRandom().nextInt(3), fee(guard)));
    }

    static boolean canHire(ServerPlayer player, Mob guard) {
        if (isHired(guard) || guard.getPersistentData().getString(NEVER).contains(player.getUUID().toString())) return false;
        if (GuardTrust.score(player, guard) < HIRE_GUARD) return false;
        ServerLevel level = player.serverLevel();
        var village = Witnesses.village(level, guard.blockPosition());
        if (village.isEmpty()) return false;
        var score = dev.villagechatter.reputation.Standing.villageScore(player, level, guard.blockPosition());
        if (score.isEmpty() || score.getAsDouble() < HIRE_VILLAGE) return false;
        var area = new net.minecraft.world.phys.AABB(village.get().pos()).inflate(64);
        if (level.getEntitiesOfClass(IronGolem.class, area, LivingEntity::isAlive).isEmpty()) return false;
        int residents = level.getEntitiesOfClass(Villager.class, area, LivingEntity::isAlive).size();
        int guards = level.getEntitiesOfClass(Mob.class, area, m -> m.isAlive() && GuardTrust.isGuard(m) && !isHired(m)).size();
        return guards - 1 >= Math.max(1, (int) Math.ceil(residents / (double) RESIDENTS_PER_GUARD));
    }

    /** @return the reply line, or null if this isn't one of ours */
    public static Component handle(ServerPlayer player, Mob guard, String action) {
        CompoundTag data = guard.getPersistentData();
        switch (action) {
            case "comp_hire" -> {
                if (!canHire(player, guard)) return line(player, "hire_no");
                int fee = fee(guard);
                if (player.getInventory().countItem(Items.EMERALD) < fee) return line(player, "hire_broke");
                take(player, fee);
                var village = Witnesses.village(player.serverLevel(), guard.blockPosition()).orElseThrow();
                data.putUUID(HIRED_BY, player.getUUID());
                data.putLong(HOME, village.pos().asLong());
                data.putString(HOME_DIM, village.dimension().location().toString());
                data.putString(MODE, "follow");
                guard.setPersistenceRequired();
                HIRED.add(guard);
                return line(player, "hired");
            }
            case "comp_follow", "comp_stay" -> {
                if (!isHiredBy(guard, player)) return null;
                data.putString(MODE, action.equals("comp_follow") ? "follow" : "stay");
                return line(player, action.equals("comp_follow") ? "follow" : "stay");
            }
            case "comp_distance" -> {
                if (!isHiredBy(guard, player)) return null;
                int next = ((data.contains(DIST) ? data.getInt(DIST) : 1) + 1) % DISTANCE.length;
                data.putInt(DIST, next);
                return line(player, "dist" + next);
            }
            case "comp_home" -> {
                if (!isHiredBy(guard, player)) return null;
                // Home is your bed. They wait there until you tell them to follow again.
                BlockPos bed = player.getRespawnPosition();
                if (bed == null) return line(player, "no_bed");
                data.putString(MODE, "going_home");
                data.putLong("villagechatter:bed", bed.asLong());
                data.putString("villagechatter:bed_dim", player.getRespawnDimension().location().toString());
                return line(player, "home");
            }
            default -> {
                return null;
            }
        }
    }

    private static Component line(ServerPlayer player, String key) {
        return Component.translatable("villagechatter.law.companion." + key + "." + player.getRandom().nextInt(3));
    }

    private static void take(ServerPlayer player, int count) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int n = Math.min(count, s.getCount());
            s.shrink(n);
            count -= n;
        }
        inv.setChanged();
    }

    // ------------------------------------------------------------------ what they see

    /**
     * Your hired guard saw you commit a crime. Instead of reporting it: a word with you (petty), disappointment
     * (assault), or the end of it (a killing). Their own opinion of you takes the hit either way.
     */
    static void saw(ServerPlayer player, Mob guard, CrimeLedger.Crime crime) {
        player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), -crime.kind.victimHit / 2, Double::sum);
        if (crime.kind.murder) unforgivable(player, guard, crime.kind);
    }

    /** What's waiting on a vouch: the crime and who saw it, in case they won't cover for you after all. */
    private record Pending(CrimeLedger.Crime crime, CrimeLedger.Standing standing, List<LivingEntity> seen, boolean golemSaw) {}

    private static final Map<UUID, Pending> PENDING = new java.util.HashMap<>();

    /**
     * Your hired guard is with you when someone sees you do something (not a killing): instead of any report, they
     * come over themselves. "We don't do that kind of thing."
     * @return true if they took it on
     */
    static boolean vouch(ServerPlayer player, CrimeLedger.Crime crime, CrimeLedger.Standing standing, List<LivingEntity> seen, boolean golemSaw) {
        if (Enforcement.busy(player) || standing.hostile()) return false;
        Mob guard = null;
        for (Mob m : HIRED) {
            if (isHiredBy(m, player) && m.isAlive() && m.level() == player.level() && m.distanceTo(player) <= Witnesses.RANGE
                    && !m.getPersistentData().getBoolean(LEAVING)) {
                guard = m;
                break;
            }
        }
        if (guard == null) return false;
        PENDING.put(player.getUUID(), new Pending(crime, standing, List.copyOf(seen), golemSaw));
        String key = crime.kind == CrimeKind.THEFT || crime.kind == CrimeKind.BED || crime.kind == CrimeKind.LEAD_ANIMAL ? "petty" : "assault";
        Enforcement.companionTalk(player, guard, key, List.of("comp_excuse", "comp_sorry"));
        return true;
    }

    /** They won't cover for you: it goes through the usual channels after all. */
    private static void noCover(ServerPlayer player) {
        Pending p = PENDING.remove(player.getUUID());
        if (p != null) Enforcement.witnessed(player, p.crime(), p.standing(), p.seen(), p.golemSaw());
    }

    static Component answer(ServerPlayer player, Mob guard, String action) {
        switch (action) {
            case "comp_excuse" -> {
                if (player.getRandom().nextDouble() < EXCUSE) {
                    // They let it slide and cover for you: keep what you took, no fine, no guards.
                    Pending p = PENDING.remove(player.getUUID());
                    if (p != null) p.standing().crimes.remove(p.crime());
                    return line(player, "excuse_ok");
                }
                player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), -2.0, Double::sum);
                noCover(player);
                return line(player, "excuse_fail");
            }
            case "comp_sorry" -> {
                noCover(player);
                return line(player, "sorry");
            }
            case "comp_plead" -> {
                if (player.getRandom().nextDouble() < PLEAD) {
                    player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), 4.0, Double::sum);
                    return line(player, "plead_ok");
                }
                leave(guard);
                return line(player, "plead_fail");
            }
            default -> {
                if (guard.getPersistentData().getBoolean("villagechatter:quitting")) leave(guard);
                noCover(player); // walked off mid-vouch
                return null;
            }
        }
    }

    /** They take everything they carried for you (it's gone), keep their own gear, and never come with you again. */
    private static void unforgivable(ServerPlayer player, Mob guard, CrimeKind kind) {
        Container pack = pack(guard);
        if (pack != null) {
            for (int i = 0; i < pack.getContainerSize(); i++) {
                ItemStack s = pack.getItem(i);
                if (!s.isEmpty() && !worn(guard, s)) pack.setItem(i, ItemStack.EMPTY);
            }
            pack.setChanged();
        }
        CompoundTag data = guard.getPersistentData();
        data.putString(NEVER, data.getString(NEVER) + player.getUUID() + ";");
        String who = kind == CrimeKind.KILL_GUARD ? "guard" : kind == CrimeKind.KILL_KID ? "kid" : kind == CrimeKind.KILL_GOLEM ? "golem" : "villager";
        LivingVillage.say(player, guard, Component.empty().append(line(player, "unforgivable." + who)).append(" ")
                .append(line(player, "unforgivable")));
        leave(guard);
    }

    private static boolean worn(Mob guard, ItemStack s) {
        for (EquipmentSlot slot : EquipmentSlot.values()) if (guard.getItemBySlot(slot) == s) return true;
        return false;
    }

    /** Guard Villagers keeps a guard's carried items in a container field; found by reflection. */
    private static Container pack(Mob guard) {
        for (Class<?> c = guard.getClass(); c != null && c != Mob.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Container.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(guard) instanceof Container box) return box;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    /** Walk off, and once out of sight, back home to normal duty. */
    private static void leave(Mob guard) {
        guard.getPersistentData().putBoolean(LEAVING, true);
        guard.getPersistentData().remove("villagechatter:quitting");
    }

    private static void release(Mob guard, boolean home) {
        CompoundTag data = guard.getPersistentData();
        data.remove(HIRED_BY);
        data.remove(MODE);
        data.remove(LEAVING);
        if (home) sendHome(guard);
    }

    private static void sendHome(Mob guard) {
        CompoundTag data = guard.getPersistentData();
        if (!data.contains(HOME) || !(guard.level() instanceof ServerLevel level)) return;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(data.getString(HOME_DIM)));
        ServerLevel home = level.getServer().getLevel(dim);
        if (home == null) return;
        BlockPos bell = BlockPos.of(data.getLong(HOME));
        BlockPos spot = home.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bell.offset(2, 0, 2));
        guard.getNavigation().stop();
        guard.setTarget(null);
        if (home == level) guard.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        else guard.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(home,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(spot), net.minecraft.world.phys.Vec3.ZERO, guard.getYRot(), guard.getXRot(),
                net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
    }

    // ------------------------------------------------------------------ every second

    public static void tick(MinecraftServer server) {
        HIRED.removeIf(m -> m.isRemoved() || !m.isAlive() || !isHired(m));
        for (Mob guard : List.copyOf(HIRED)) tickGuard(server, guard);
    }

    private static void tickGuard(MinecraftServer server, Mob guard) {
        CompoundTag data = guard.getPersistentData();
        UUID owner = data.getUUID(HIRED_BY);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (data.getBoolean(LEAVING)) {
            if (player == null || player.level() != guard.level() || (guard.distanceTo(player) > 16 && !player.hasLineOfSight(guard))
                    || guard.distanceTo(player) > 32) {
                release(guard, true);
                return;
            }
            var away = guard.position().add(guard.position().subtract(player.position()).normalize().scale(10));
            guard.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
            return;
        }
        String mode = data.getString(MODE);
        if (mode.equals("going_home") || mode.equals("home")) {
            atBed(server, guard, player, mode);
            return;
        }
        if (player == null) return;
        // They quit when your standing with them sinks too low, once it's quiet.
        if (GuardTrust.score(player, guard) < QUIT && !Enforcement.busy(player) && guard.getTarget() == null
                && guard.level().getEntitiesOfClass(Mob.class, guard.getBoundingBox().inflate(16), m -> m instanceof Enemy && m.isAlive()).isEmpty()
                && guard.distanceTo(player) < 12) {
            data.putBoolean("villagechatter:quitting", true);
            Enforcement.companionTalk(player, guard, "leave", List.of("comp_plead"));
            return;
        }
        if (!mode.equals("follow")) return;
        // Fight beside you: whatever hurt you, or whatever you're fighting.
        LivingEntity foe = player.getLastHurtByMob() != null && player.getLastHurtByMob().isAlive() ? player.getLastHurtByMob() : player.getLastHurtMob();
        if (foe != null && foe.isAlive() && foe != guard && !(foe instanceof Villager) && !(foe instanceof IronGolem)
                && !GuardTrust.isGuard(foe) && foe.distanceTo(player) < 16 && guard.getTarget() == null) {
            guard.setTarget(foe);
        }
        if (guard.getTarget() != null) return;
        if (player.level() != guard.level() || guard.distanceTo(player) > 32) {
            if (player.level() == guard.level()) guard.teleportTo(player.getX(), player.getY(), player.getZ());
            return;
        }
        double keep = DISTANCE[Math.floorMod(data.contains(DIST) ? data.getInt(DIST) : 1, DISTANCE.length)];
        if (guard.distanceTo(player) > keep) guard.getNavigation().moveTo(player, 1.0);
        else guard.getNavigation().stop();
        advocate(player, guard);
    }

    /** On their way to your bed (out of your sight they just get there), then waiting by it. */
    private static void atBed(MinecraftServer server, Mob guard, ServerPlayer player, String mode) {
        CompoundTag data = guard.getPersistentData();
        BlockPos bed = BlockPos.of(data.getLong("villagechatter:bed"));
        ServerLevel home = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(data.getString("villagechatter:bed_dim"))));
        if (home == null) return;
        if (mode.equals("going_home")) {
            boolean seen = player != null && player.level() == guard.level() && player.hasLineOfSight(guard) && guard.distanceTo(player) < 24;
            if (seen) {
                var away = guard.position().add(guard.position().subtract(player.position()).normalize().scale(10));
                guard.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
                return;
            }
            data.putString(MODE, "home");
            moveTo(guard, home, bed.above());
            return;
        }
        if (guard.level() == home && guard.getTarget() == null && guard.blockPosition().distSqr(bed) > 25) {
            guard.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 0.7);
        }
    }

    private static void moveTo(Mob guard, ServerLevel level, BlockPos pos) {
        guard.getNavigation().stop();
        guard.setTarget(null);
        if (guard.level() == level) guard.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        else guard.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(level,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(pos), net.minecraft.world.phys.Vec3.ZERO, guard.getYRot(), guard.getXRot(),
                net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
    }

    /** A village you've never been to, with your guard beside you: they put in a word for you with the whole village. */
    private static void advocate(ServerPlayer player, Mob guard) {
        ServerLevel level = player.serverLevel();
        var village = Witnesses.village(level, player.blockPosition());
        if (village.isEmpty()) return;
        VillageStanding vs = VillageStanding.get(level);
        BlockPos anchor = village.get().pos();
        if (vs.get(anchor, player.getUUID(), VillageStanding.Group.VISITED) > 0) return;
        vs.add(anchor, player.getUUID(), VillageStanding.Group.VISITED, 1);
        dev.villagechatter.reputation.faction.FactionBook.firstVisit(player, level, anchor);
        if (guard.distanceTo(player) > Witnesses.RANGE) return;
        vs.add(anchor, player.getUUID(), VillageStanding.Group.ADVOCACY, ADVOCACY);
        LivingVillage.say(player, guard, line(player, "advocate"));
    }

    /** Something your hired guards love to see (a raid won, a pillager down). */
    static void cheer(ServerPlayer player, double amount) {
        for (Mob guard : HIRED) {
            if (isHiredBy(guard, player) && guard.level() == player.level() && guard.distanceTo(player) <= 32) {
                player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), amount, Double::sum);
            }
        }
    }

    /** A good deed your hired guard saw: a word of praise, and they think a bit better of you. */
    static void sawGoodDeed(ServerPlayer player, LivingEntity where) {
        for (Mob guard : player.serverLevel().getEntitiesOfClass(Mob.class, where.getBoundingBox().inflate(16),
                m -> isHiredBy(m, player) && m.hasLineOfSight(where))) {
            player.getData(QuestRegistries.GOODWILL).points.merge(guard.getUUID(), 1.0, Double::sum);
            if (player.getRandom().nextInt(3) == 0) LivingVillage.say(player, guard, line(player, "praise"));
        }
    }

    /** For tests and the village standing: hired guards no longer speak for any village. */
    public static boolean speaksForVillage(LivingEntity guard) {
        return !isHired(guard);
    }
}

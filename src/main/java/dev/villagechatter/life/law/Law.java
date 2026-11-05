package dev.villagechatter.life.law;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Where crimes are noticed (LAW_AND_ORDER.md). A crime only counts when someone saw it: everyone who did thinks less of
 * you at once (the one you wronged the most), and the village and its guards take their share when it's settled.
 * What happens next (reporting, arrests) is {@link Enforcement}'s job.
 */
public final class Law {
    private Law() {}

    /** Witnesses take this share of the victim's hit, the village a quarter. */
    private static final double WITNESS_SHARE = 0.5, VILLAGE_SHARE = 0.25;
    /** A second offence of the same kind within this long piles onto the first. */
    private static final long REPEAT_WINDOW = 24000;
    /** Friends (standing at least this) talk to you about petty crimes before telling anyone. */
    public static final double FRIEND = 20;

    public static boolean enabled() {
        return ChatterConfig.LAW_AND_ORDER.get();
    }

    /**
     * Someone may have seen {@code player} commit {@code kind} against {@code victim} (may be null, e.g. a chest).
     * @return the crime if anyone saw it
     */
    public static Optional<CrimeLedger.Crime> commit(ServerPlayer player, CrimeKind kind, LivingEntity victim,
                                                     List<ItemStack> stolen, BlockPos chest) {
        if (!enabled() || player.isCreative() || player.isSpectator()) return Optional.empty();
        ServerLevel level = player.serverLevel();
        Optional<GlobalPos> village = Witnesses.village(level, victim != null ? victim.blockPosition() : player.blockPosition());
        if (village.isEmpty()) return Optional.empty();

        List<LivingEntity> seen = new ArrayList<>(Witnesses.of(level, player));
        if (victim != null && victim.isAlive() && !seen.contains(victim) && Witnesses.canWitness(victim)) seen.add(0, victim);
        if (seen.isEmpty()) return Optional.empty();

        long now = level.getGameTime();
        CrimeLedger.Standing standing = CrimeLedger.get(level.getServer()).standing(player.getUUID(), village.get());
        CrimeLedger.Crime crime = standing.crimes.stream()
                .filter(c -> c.kind == kind && now - c.time < REPEAT_WINDOW
                        && (victim == null ? c.victim == null : victim.getUUID().equals(c.victim)))
                .findFirst().orElse(null);
        if (crime == null) {
            crime = new CrimeLedger.Crime(kind, village.get(), victim == null ? null : victim.getUUID(), now);
            crime.fine = kind.rollFine(level.random) + (kind == CrimeKind.THEFT ? stolenValue(stolen) : 0);
            standing.crimes.add(crime);
        } else {
            crime.hits++;
            crime.fine += kind == CrimeKind.THEFT ? stolenValue(stolen) : kind.repeatFine(level.random);
        }
        if (stolen != null) for (ItemStack s : stolen) if (!s.isEmpty()) crime.stolen.add(s.copy());
        if (chest != null) crime.chest = chest.immutable();

        // Everyone who saw it thinks less of you right away; golems and guards as one (anger one, anger all).
        double hit = kind.victimHit;
        if (dev.villagechatter.reputation.faction.FactionBook.enabled()) {
            double standingHere = dev.villagechatter.reputation.Standing.villageScore(player, level, village.get().pos()).orElse(0);
            hit *= dev.villagechatter.reputation.faction.FactionBook.crimeScale(kind, seen.size(), standingHere);
        }
        double golems = 0, guards = 0;
        for (LivingEntity w : List.copyOf(seen)) {
            if (w instanceof net.minecraft.world.entity.Mob m && Companion.isHiredBy(m, player)) {
                // Your own hired guard: they think less of you, and never report you (see Companion.vouch).
                seen.remove(w);
                Companion.saw(player, m, crime);
                if (!crime.kind.murder) crime.villageHit += hit * VILLAGE_SHARE; // the village still hears of it
                continue;
            }
            double share = w == victim ? hit : hit * WITNESS_SHARE;
            int own = w instanceof Villager ? (int) Math.round(share) : 0;
            crime.witnesses.merge(w.getUUID(), own, Integer::sum);
            if (w instanceof Villager v) Reputation.adjust(player, v, -own, false);
            else if (w instanceof IronGolem) golems = Math.max(golems, share);
            else if (GuardTrust.isGuard(w)) {
                guards = Math.max(guards, share);
                crime.seenByGuard = true;
            }
        }
        VillageStanding vs = VillageStanding.get(level);
        vs.add(village.get().pos(), player.getUUID(), VillageStanding.Group.GOLEMS, -golems);
        vs.add(village.get().pos(), player.getUUID(), VillageStanding.Group.GUARDS, -guards);
        crime.villageHit += hit * VILLAGE_SHARE;
        if (!crime.seenByGuard) crime.guardHit += hit * WITNESS_SHARE;

        // Your hired guard is with you: they deal with it themselves instead of anyone reporting it.
        if (!kind.murder && Companion.vouch(player, crime, standing, seen, golems > 0)) return Optional.of(crime);
        Enforcement.witnessed(player, crime, standing, seen, golems > 0);
        return Optional.of(crime);
    }

    /** Settle the held-back hits (paid up, caught lying, or fought). */
    public static void landHits(ServerPlayer player, CrimeLedger.Crime crime, Entity near) {
        if (crime.villageHit > 0) Reputation.addVillageGoodwill(player, near, -crime.villageHit);
        if (crime.guardHit > 0) {
            VillageStanding.get(player.serverLevel()).add(crime.village.pos(), player.getUUID(), VillageStanding.Group.GUARDS, -crime.guardHit);
        }
        crime.villageHit = crime.guardHit = 0;
    }

    /**
     * A good deed in a village: its golems (and guards) think better of you too. Kindness to kids, repairing golems,
     * building golems, helping guards out, shoring up the walls.
     */
    public static void goodDeed(ServerPlayer player, Entity where, double golems, double guards) {
        if (!enabled() || !(where.level() instanceof ServerLevel level)) return;
        if (where instanceof LivingEntity living) Companion.sawGoodDeed(player, living);
        Witnesses.village(level, where.blockPosition()).ifPresent(v -> {
            VillageStanding vs = VillageStanding.get(level);
            vs.add(v.pos(), player.getUUID(), VillageStanding.Group.GOLEMS, golems);
            vs.add(v.pos(), player.getUUID(), VillageStanding.Group.GUARDS, guards);
        });
    }

    private static final double SECURITY_GOODWILL = 0.1, SECURITY_DAILY = 2;
    private static final java.util.Map<java.util.UUID, double[]> SECURITY_TODAY = new java.util.HashMap<>();

    /** Every wall, gate or door you put up in a village counts for a little (capped per day), quest or not. */
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player) || player.isCreative()) return;
        if (!dev.villagechatter.life.SecurityBlocks.isDefensive(event.getPlacedBlock())) return;
        ServerLevel level = player.serverLevel();
        if (Witnesses.village(level, event.getPos()).isEmpty()) return;
        long day = level.getDayTime() / 24000;
        double[] today = SECURITY_TODAY.computeIfAbsent(player.getUUID(), k -> new double[] {day, 0});
        if (today[0] != day) {
            today[0] = day;
            today[1] = 0;
        }
        if (today[1] >= SECURITY_DAILY) return;
        today[1] += SECURITY_GOODWILL;
        goodDeed(player, player, SECURITY_GOODWILL / 2, SECURITY_GOODWILL);
        Reputation.addVillageGoodwill(player, player, SECURITY_GOODWILL / 2);
    }

    /** Built a golem for a village: the golems are glad of the company. */
    public static void onGolemBuilt(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem) || !golem.isPlayerCreated() || event.loadedFromDisk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer builder = (ServerPlayer) level.getNearestPlayer(golem, 8);
        if (builder != null) goodDeed(builder, golem, 5, 1);
    }

    /** Stolen goods add to the fine: roughly an emerald per stack-worth of ordinary stuff. */
    static int stolenValue(List<ItemStack> stolen) {
        if (stolen == null) return 0;
        double v = 0;
        for (ItemStack s : stolen) {
            if (s.is(Items.EMERALD) || s.is(Items.DIAMOND) || s.is(Items.EMERALD_BLOCK) || s.is(Items.DIAMOND_BLOCK)) v += s.getCount();
            else v += s.getCount() / (double) Math.max(1, s.getMaxStackSize()) * (s.getRarity().ordinal() + 1);
        }
        return (int) Math.ceil(v);
    }

    // ------------------------------------------------------------------ what counts

    /** Hitting a villager, kid, guard or golem. */
    public static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (event.getNewDamage() <= 0 || victim.level().isClientSide()) return;
        CrimeKind kind = assault(victim);
        if (kind == null) return;
        ServerPlayer player = Blame.culprit(event.getSource());
        if (player == null || victim.isDeadOrDying()) return; // a killing blow is the death's crime
        if (dev.villagechatter.life.FriendlyFire.forgiven(player, victim)) return; // "I'm on your side!"
        commit(player, kind, victim, null, null);
    }

    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        CrimeKind kind = victim instanceof Villager v ? (v.isBaby() ? CrimeKind.KILL_KID : CrimeKind.KILL_VILLAGER)
                : victim instanceof IronGolem ? CrimeKind.KILL_GOLEM : GuardTrust.isGuard(victim) ? CrimeKind.KILL_GUARD : null;
        WitnessMemory.died(victim);
        if (kind == null) return;
        ServerPlayer player = Blame.culprit(event.getSource());
        boolean seen = player != null && commit(player, kind, victim, null, null).isPresent();
        Grief.died(victim, seen ? player : null);
    }

    /** Leading a golem or a village animal away. */
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItemStack().is(Items.LEAD)) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        boolean golem = target instanceof IronGolem ig && !ig.isPlayerCreated();
        boolean animal = target instanceof Animal && target.getTags().contains(Witnesses.VILLAGE_ANIMAL);
        if (!golem && !animal) return;
        player.server.execute(() -> {
            if (target instanceof net.minecraft.world.entity.Leashable l && l.getLeashHolder() == player) {
                commit(player, golem ? CrimeKind.LEAD_GOLEM : CrimeKind.LEAD_ANIMAL, target, null, null);
            }
        });
    }

    private static CrimeKind assault(LivingEntity victim) {
        if (victim instanceof Animal && victim.getTags().contains(Witnesses.VILLAGE_ANIMAL)) return CrimeKind.ASSAULT_ANIMAL;
        if (victim instanceof Villager v) return v.isBaby() ? CrimeKind.ASSAULT_KID : CrimeKind.ASSAULT_VILLAGER;
        if (victim instanceof IronGolem) return CrimeKind.ASSAULT_GUARD;
        return GuardTrust.isGuard(victim) ? CrimeKind.ASSAULT_GUARD : null;
    }
}

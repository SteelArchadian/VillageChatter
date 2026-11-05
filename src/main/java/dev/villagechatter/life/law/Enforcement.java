package dev.villagechatter.life.law;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.LivingVillage;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.quest.Reputation;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * What happens after a crime is seen (LAW_AND_ORDER.md, sections 3 to 5).
 *  - A witness walks to the nearest guard or golem and tells them. A village horse tells a guard once it wanders
 *    near one.
 *  - A golem that knows shadows you around the village. Do it again and it attacks; petty theft only on the third
 *    item. If it can't reach you, it sends a guard.
 *  - A guard who knows comes over and stops you for a talk you can't skip (Skyrim style): deny, ask him to let it
 *    slide, bribe, pay, decline or fight. Walking off or pressing Esc is resisting arrest.
 *  - Resist and every guard and golem in that village attacks you on sight, until you've been gone a full day.
 *  - A friend who saw something petty talks to you first. Own up and it stays between you; double down and they
 *    report it.
 *  - After a raid, a guard who fought beside you comes over for a word.
 */
public final class Enforcement {
    private Enforcement() {}

    private static final long DAY = 24000;
    private static final double DENY_FIRST = 0.30, DENY_AGAIN = 0.10, SLIDE = 0.10, BRIBE = 0.70;
    /** Each failed check: a little with the guards and the village. Resisting: more. */
    private static final double FAIL_GUARDS = 2, FAIL_VILLAGE = 1, RESIST_GUARDS = 6, RESIST_VILLAGE = 3;
    private static final int GOLEM_THEFT_LIMIT = 3;
    private static final double AUTHORITY_RANGE = 64, VILLAGE_EDGE = 80, TALK_RANGE = 3;
    /** Seconds before someone sent over gives up (and is tried again later). */
    private static final int WALK_TIMEOUT = 90;

    static final class Report {
        final UUID player;
        /** Stopped by the player mid-run (Intercept): they stand and talk instead of walking. */
        boolean held;
        final CrimeLedger.Crime crime;
        LivingEntity teller;
        /** Everyone else who saw it: if the teller doesn't make it, the next one goes. */
        final List<LivingEntity> others = new ArrayList<>();
        LivingEntity to;
        int seconds;

        Report(LivingEntity teller, UUID player, CrimeLedger.Crime crime, LivingEntity to) {
            this.teller = teller;
            this.player = player;
            this.crime = crime;
            this.to = to;
        }
    }

    private static final class Walk {
        final Mob walker;
        final GlobalPos village;
        final boolean known;
        final CrimeLedger.Crime crime; // a friend's word: the one crime they saw
        int seconds;

        Walk(Mob walker, GlobalPos village, boolean known, CrimeLedger.Crime crime) {
            this.walker = walker;
            this.village = village;
            this.known = known;
            this.crime = crime;
        }
    }

    private static final class Shadow {
        final IronGolem golem;
        final GlobalPos village;
        int stuck;

        Shadow(IronGolem golem, GlobalPos village) {
            this.golem = golem;
            this.village = village;
        }
    }

    private enum TalkKind { ARREST, FRIEND, BANTER, COMPANION }

    private static final class Talk {
        final TalkKind kind;
        final LivingEntity speaker;
        final GlobalPos village;
        final List<CrimeLedger.Crime> crimes;
        int fine, denies;
        boolean deny, slide, bribe, broke;

        Talk(TalkKind kind, LivingEntity speaker, GlobalPos village, List<CrimeLedger.Crime> crimes) {
            this.kind = kind;
            this.speaker = speaker;
            this.village = village;
            this.crimes = crimes;
        }
    }

    private record Patrol(Mob guard, BlockPos at, long until) {}

    private static final List<Report> REPORTS = new ArrayList<>();
    /** By player: a guard coming to arrest them, or a friend coming for a word. */
    private static final Map<UUID, Walk> WALKS = new HashMap<>();
    private static final Map<UUID, Shadow> SHADOWS = new HashMap<>();
    private static final Map<UUID, Talk> TALKS = new HashMap<>();
    private static final List<Patrol> PATROLS = new ArrayList<>();
    /** By player: no guard is sent before this game time (they just talked, or someone gave up). */
    private static final Map<UUID, Long> NEXT_ARREST = new HashMap<>();
    private static final Map<UUID, Long> NEXT_BANTER = new HashMap<>();

    // ------------------------------------------------------------------ a crime was seen

    static void witnessed(ServerPlayer player, CrimeLedger.Crime crime, CrimeLedger.Standing standing,
                          List<LivingEntity> seen, boolean golemSaw) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (crime.kind.murder) {
            // Like the bell: everyone who saw it runs for cover.
            for (LivingEntity w : seen) if (w instanceof Villager v) v.getBrain().setMemory(MemoryModuleType.HEARD_BELL_TIME, now);
        }
        if (standing.hostile()) return; // they're already after you
        if (crime.seenByGuard) {
            crime.reported = true;
            Mob guard = (Mob) seen.stream().filter(GuardTrust::isGuard).findFirst().orElseThrow();
            sendGuard(player, guard, crime.village, false);
            return;
        }
        if (golemSaw) {
            IronGolem golem = (IronGolem) seen.stream().filter(e -> e instanceof IronGolem).findFirst().orElseThrow();
            toldGolem(player, golem, crime, standing);
            return;
        }
        if (petty(crime.kind) && standing.forgivenUntil <= now && !WALKS.containsKey(player.getUUID())) {
            for (LivingEntity w : seen) {
                if (w instanceof Villager v && dev.villagechatter.reputation.Standing.score(player, v) >= Law.FRIEND - crime.kind.victimHit) {
                    WALKS.put(player.getUUID(), new Walk(v, crime.village, false, crime));
                    return;
                }
            }
        }
        LivingEntity teller = seen.stream().filter(e -> e instanceof Villager).findFirst()
                .orElse(seen.stream().filter(e -> e instanceof AbstractHorse).findFirst().orElse(null));
        if (teller == null) return;
        Report r = tell(player, teller, crime);
        if (r != null) for (LivingEntity w : seen) if (w != teller && (w instanceof Villager || w instanceof AbstractHorse)) r.others.add(w);
    }

    /** The report this teller is running to make about this player, if any. */
    static Report reportBy(UUID player, LivingEntity teller) {
        for (Report r : REPORTS) if (r.player.equals(player) && r.teller == teller && !r.crime.reported) return r;
        return null;
    }

    /** The teller was talked out of it: the next witness goes instead, or nobody does. */
    static void silenced(Report r) {
        r.held = false;
        r.others.removeIf(w -> !w.isAlive() || !r.crime.witnesses.containsKey(w.getUUID()));
        if (r.others.isEmpty()) REPORTS.remove(r);
        else {
            r.teller = r.others.remove(0);
            r.seconds = 0;
        }
    }

    /** For GameTests: someone is on their way to report this crime. */
    static Report startReport(ServerPlayer player, LivingEntity teller, CrimeLedger.Crime crime, LivingEntity to) {
        Report r = new Report(teller, player.getUUID(), crime, to);
        REPORTS.add(r);
        return r;
    }

    private static Report tell(ServerPlayer player, LivingEntity teller, CrimeLedger.Crime crime) {
        LivingEntity to = nearestAuthority(teller);
        if (to == null && !(teller instanceof AbstractHorse)) return null; // nobody to tell; the witnesses just remember
        Report r = new Report(teller, player.getUUID(), crime, to);
        REPORTS.add(r);
        return r;
    }

    private static boolean petty(CrimeKind kind) {
        return kind == CrimeKind.THEFT || kind == CrimeKind.BED || kind == CrimeKind.LEAD_ANIMAL;
    }

    /** A golem knows: it watches you. Already watching? Then that was one too many. */
    private static void toldGolem(ServerPlayer player, IronGolem golem, CrimeLedger.Crime crime, CrimeLedger.Standing standing) {
        crime.reported = true;
        crime.toGolem = true;
        Shadow shadow = SHADOWS.get(player.getUUID());
        if (shadow != null && shadow.village.equals(crime.village)) {
            if (crime.kind == CrimeKind.THEFT) {
                standing.watchedThefts += crime.stolen.stream().mapToInt(ItemStack::getCount).sum() > 0 ? 1 : 0;
                if (standing.watchedThefts < GOLEM_THEFT_LIMIT) return;
            }
            turnHostile(player, standing, golem);
            return;
        }
        standing.watchedThefts = crime.kind == CrimeKind.THEFT ? 1 : 0;
        SHADOWS.put(player.getUUID(), new Shadow(golem, crime.village));
        golem.getNavigation().stop();
        golem.setTarget(null);
    }

    private static LivingEntity nearestGuard(LivingEntity from) {
        LivingEntity best = null;
        for (Mob m : from.level().getEntitiesOfClass(Mob.class, from.getBoundingBox().inflate(AUTHORITY_RANGE),
                m -> m.isAlive() && GuardTrust.isGuard(m) && !Companion.isHired(m))) {
            if (best == null || m.distanceToSqr(from) < best.distanceToSqr(from)) best = m;
        }
        return best;
    }

    private static LivingEntity nearestAuthority(LivingEntity from) {
        LivingEntity best = null;
        for (LivingEntity e : from.level().getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(AUTHORITY_RANGE),
                e -> e.isAlive() && (e instanceof IronGolem || GuardTrust.isGuard(e)))) {
            if (best == null || e.distanceToSqr(from) < best.distanceToSqr(from)) best = e;
        }
        return best;
    }

    private static void sendGuard(ServerPlayer player, Mob guard, GlobalPos village, boolean known) {
        if (TALKS.containsKey(player.getUUID())) return;
        Walk current = WALKS.get(player.getUUID());
        if (current != null && current.crime == null) return; // a guard is already on the way
        WALKS.put(player.getUUID(), new Walk(guard, village, known, null));
    }

    // ------------------------------------------------------------------ every second

    public static void tick(MinecraftServer server, int now) {
        if (now % 20 != 0 || !Law.enabled()) return;
        long time = server.overworld().getGameTime();
        CrimeLedger ledger = CrimeLedger.get(server);
        if (now % 1200 == 0) ledger.forget(time);

        for (Iterator<Report> it = REPORTS.iterator(); it.hasNext(); ) {
            Report r = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(r.player);
            if (player != null && !r.teller.isAlive() && !r.crime.reported) {
                // Silenced before they could tell anyone: the next witness goes instead, if there is one.
                r.others.removeIf(w -> !w.isAlive());
                if (!r.others.isEmpty()) {
                    r.teller = r.others.remove(0);
                    r.seconds = 0;
                    continue;
                }
            }
            if (player == null || !r.teller.isAlive() || r.crime.reported) {
                it.remove();
                continue;
            }
            if (r.held) continue;
            if (r.teller instanceof AbstractHorse) {
                // A horse can't go and tell anyone: it does once it happens to be near a guard.
                LivingEntity guard = nearestAuthority(r.teller);
                if (guard == null || !GuardTrust.isGuard(guard) || guard.distanceTo(r.teller) > 12) continue;
                r.to = guard;
            } else if (r.to == null || !r.to.isAlive()) {
                r.to = nearestAuthority(r.teller);
                if (r.to == null) {
                    it.remove();
                    continue;
                }
            }
            r.seconds++;
            if (r.teller.distanceTo(r.to) > TALK_RANGE && r.seconds < WALK_TIMEOUT && !(r.teller instanceof AbstractHorse)) {
                walkTo(r.teller, r.to, 0.7F);
                continue;
            }
            it.remove();
            r.crime.reported = true;
            r.crime.witnesses.putIfAbsent(r.to.getUUID(), 0); // now they know too
            CrimeLedger.Standing standing = ledger.standing(player.getUUID(), r.crime.village);
            if (r.to instanceof IronGolem golem) toldGolem(player, golem, r.crime, standing);
            else if (r.to instanceof Mob guard) sendGuard(player, guard, r.crime.village, false);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) tickPlayer(player, ledger, time);

        PATROLS.removeIf(p -> !p.guard().isAlive() || p.until() < time);
        for (Patrol p : PATROLS) {
            if (p.guard().getTarget() == null && p.guard().blockPosition().distSqr(p.at()) > 36) p.guard().getNavigation().moveTo(
                    p.at().getX() + 0.5, p.at().getY(), p.at().getZ() + 0.5, 0.7);
        }
    }

    private static void tickPlayer(ServerPlayer player, CrimeLedger ledger, long time) {
        UUID id = player.getUUID();
        ServerLevel level = player.serverLevel();

        // Hostile villages: in town they come for you; a full day away cools them down.
        for (Map.Entry<GlobalPos, CrimeLedger.Standing> e : ledger.all(id).entrySet()) {
            CrimeLedger.Standing s = e.getValue();
            if (!s.hostile()) continue;
            boolean here = e.getKey().dimension() == level.dimension() && e.getKey().pos().closerThan(player.blockPosition(), VILLAGE_EDGE);
            if (!here) {
                if (s.leftAt == 0) s.leftAt = time;
                giveUpChase(level, player, e.getKey());
                continue;
            }
            if (s.leftAt > 0 && time - s.leftAt >= DAY) {
                s.hostileUntil = 0; // they've calmed down; the next guard who spots you still knows your face
                s.leftAt = 0;
                ledger.setDirty();
                continue;
            }
            s.leftAt = 0;
            if (player.isCreative() || player.isSpectator()) continue;
            for (Mob m : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32),
                    m -> m.isAlive() && (m instanceof IronGolem || GuardTrust.isGuard(m)))) {
                if (m.getTarget() != player) m.setTarget(player);
            }
        }

        // A golem shadowing you.
        Shadow shadow = SHADOWS.get(id);
        if (shadow != null) {
            CrimeLedger.Standing s = ledger.standing(id, shadow.village);
            boolean here = shadow.village.dimension() == level.dimension()
                    && shadow.village.pos().closerThan(player.blockPosition(), VILLAGE_EDGE);
            if (!shadow.golem.isAlive() || !here || s.hostile()) {
                SHADOWS.remove(id);
                if (!shadow.golem.isAlive()) s.crimes.removeIf(c -> c.toGolem); // only it knew
                else if (!here) settleGolemCrimes(player, s, shadow.golem); // you left town: it lets you go
            } else if (shadow.golem.getTarget() == null) {
                shadow.golem.getLookControl().setLookAt(player);
                if (shadow.golem.distanceTo(player) > 4) {
                    boolean moving = shadow.golem.getNavigation().moveTo(player, 0.8);
                    shadow.stuck = moving ? 0 : shadow.stuck + 1;
                } else shadow.golem.getNavigation().stop();
                if (shadow.stuck >= 10) {
                    // It can't reach you: it sends a guard instead.
                    SHADOWS.remove(id);
                    s.crimes.forEach(c -> { if (c.toGolem) c.toGolem = false; });
                    LivingEntity guard = nearestAuthority(player);
                    if (guard instanceof Mob m && GuardTrust.isGuard(m)) sendGuard(player, m, shadow.village, false);
                }
            }
        }

        // Someone on their way to you.
        Walk walk = WALKS.get(id);
        if (walk != null) {
            walk.seconds++;
            if (!walk.walker.isAlive() || walk.walker.level() != level || walk.seconds > WALK_TIMEOUT) {
                WALKS.remove(id);
                if (walk.crime != null && walk.walker.isAlive()) tell(player, walk.walker, walk.crime); // gave up on you: tells instead
                else NEXT_ARREST.put(id, time + 600);
            } else if (!TALKS.containsKey(id) && walk.walker.distanceTo(player) <= TALK_RANGE && walk.walker.hasLineOfSight(player)) {
                WALKS.remove(id);
                if (walk.crime != null) openFriend(player, (Villager) walk.walker, walk.crime);
                else openArrest(player, walk.walker, walk.village, walk.known, ledger);
            } else if (!TALKS.containsKey(id)) {
                walk.walker.setTarget(null);
                walk.walker.getLookControl().setLookAt(player);
                if (walk.walker instanceof Villager v) walkTo(v, player, 0.6F);
                else walk.walker.getNavigation().moveTo(player, 1.0);
            }
        }

        // First visits count (a hired guard's word only helps in villages you've never been to).
        if (time % 100 < 20) Witnesses.village(level, player.blockPosition()).ifPresent(v -> {
            VillageStanding vs = VillageStanding.get(level);
            if (vs.get(v.pos(), id, VillageStanding.Group.VISITED) == 0 && !Companion.hasFollower(player)) {
                vs.add(v.pos(), id, VillageStanding.Group.VISITED, 1);
                dev.villagechatter.reputation.faction.FactionBook.firstVisit(player, level, v.pos());
            }
        });

        // Wanted: the first guard who spots you comes over ("Hey, I know you.").
        if (walk == null && !TALKS.containsKey(id) && NEXT_ARREST.getOrDefault(id, 0L) <= time
                && !player.isCreative() && !player.isSpectator()) {
            Optional<GlobalPos> village = Witnesses.village(level, player.blockPosition());
            if (village.isEmpty()) return;
            CrimeLedger.Standing s = ledger.all(id).get(village.get());
            if (s == null || s.hostile() || !s.wanted()) return;
            boolean known = s.crimes.stream().anyMatch(c -> time - c.time > DAY / 2);
            LivingEntity spotter = null;
            for (LivingEntity w : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(Witnesses.RANGE),
                    w -> Witnesses.canWitness(w) && !(w instanceof AbstractHorse) && !(w instanceof Mob m && Companion.isHired(m)))) {
                if (!Witnesses.sees(w, player)) continue;
                if (GuardTrust.isGuard(w) && w instanceof Mob guard && guard.getTarget() == null) {
                    sendGuard(player, guard, village.get(), known);
                    return;
                }
                if (spotter == null) spotter = w;
            }
            // A villager who recognizes you runs for the watch; a golem keeps its eyes on you while one comes.
            if (spotter != null && nearestGuard(spotter) instanceof Mob guard) {
                if (spotter instanceof IronGolem golem && !SHADOWS.containsKey(id)) SHADOWS.put(id, new Shadow(golem, village.get()));
                if (spotter instanceof Villager v) walkTo(v, guard, 0.7F);
                sendGuard(player, guard, village.get(), known);
            }
        }
    }

    /** Past the village limits plus this far, they give up the chase and head back. */
    private static final double CHASE_BEYOND = 64;

    private static void giveUpChase(ServerLevel level, ServerPlayer player, GlobalPos village) {
        if (village.dimension() != level.dimension()) return;
        BlockPos home = village.pos();
        boolean tooFar = !home.closerThan(player.blockPosition(), VILLAGE_EDGE + CHASE_BEYOND);
        for (Mob m : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(48),
                m -> m.getTarget() == player && (m instanceof IronGolem || GuardTrust.isGuard(m)) && !Companion.isHired(m))) {
            if (!tooFar && m.hasLineOfSight(player)) continue; // still on your heels
            if (tooFar || !home.closerThan(m.blockPosition(), VILLAGE_EDGE + CHASE_BEYOND)) {
                m.setTarget(null);
                m.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
            }
        }
    }

    private static void walkTo(LivingEntity who, Entity target, float speed) {
        if (who instanceof Villager v) {
            v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(target, false), speed, 2));
        } else if (who instanceof Mob m) {
            m.getNavigation().moveTo(target, speed + 0.3);
        }
    }

    /** You left town while a golem watched you: it lets the petty stuff go, and the village remembers it. */
    private static void settleGolemCrimes(ServerPlayer player, CrimeLedger.Standing s, Entity near) {
        for (Iterator<CrimeLedger.Crime> it = s.crimes.iterator(); it.hasNext(); ) {
            CrimeLedger.Crime c = it.next();
            if (!c.toGolem) continue;
            Law.landHits(player, c, near);
            it.remove();
        }
        s.watchedThefts = 0;
    }

    private static void turnHostile(ServerPlayer player, CrimeLedger.Standing s, LivingEntity by) {
        s.hostileUntil = Long.MAX_VALUE;
        s.leftAt = 0;
        SHADOWS.remove(player.getUUID());
        WALKS.remove(player.getUUID());
        for (CrimeLedger.Crime c : s.crimes) Law.landHits(player, c, by);
        if (by instanceof Mob m) m.setTarget(player);
    }

    // ------------------------------------------------------------------ guards and golems hold their fire

    /**
     * A golem or guard about to attack a player stops and talks first (the Skyrim arrest), unless that player is
     * already hostile here. Only while law and order is on.
     */
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!Law.enabled() || !(event.getNewAboutToBeSetTarget() instanceof ServerPlayer player)) return;
        LivingEntity mob = event.getEntity();
        if (!(mob instanceof IronGolem) && !GuardTrust.isGuard(mob)) return;
        if (player.isCreative() || player.isSpectator()) return;
        Optional<GlobalPos> village = Witnesses.village(player.serverLevel(), mob.blockPosition());
        if (village.isEmpty()) return;
        CrimeLedger.Standing s = CrimeLedger.get(player.server).all(player.getUUID()).get(village.get());
        if (s != null && s.hostile() && !TALKS.containsKey(player.getUUID())) return;
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ the arrest

    private static void openArrest(ServerPlayer player, Mob guard, GlobalPos village, boolean known, CrimeLedger ledger) {
        CrimeLedger.Standing s = ledger.standing(player.getUUID(), village);
        List<CrimeLedger.Crime> crimes = new ArrayList<>(s.crimes.stream().filter(c -> c.reported && !c.toGolem).toList());
        if (crimes.isEmpty() || s.hostile()) return;
        Talk t = new Talk(TalkKind.ARREST, guard, village, crimes);
        CrimeKind worst = crimes.stream().map(c -> c.kind).max(Enum::compareTo).orElseThrow();
        boolean murder = worst.murder;
        t.fine = crimes.stream().mapToInt(c -> c.fine).sum();
        t.deny = !murder && crimes.stream().noneMatch(c -> c.seenByGuard);
        t.slide = !murder && crimes.stream().allMatch(c -> c.kind.slideable);
        t.bribe = !murder;
        TALKS.put(player.getUUID(), t);
        if (player.isSleeping()) player.stopSleepInBed(true, true); // "Out of that bed."
        guard.getNavigation().stop();
        guard.setTarget(null);
        LivingVillage.babble(guard, 4, 0, 0.1F, 1.0F);
        String opener = known ? "known" : "accost." + worst.name().toLowerCase(java.util.Locale.ROOT);
        Component line = Component.empty().append(line(player, opener)).append(" ")
                .append(Component.translatable("villagechatter.law.fine." + player.getRandom().nextInt(3), t.fine));
        ConversationManager.openScripted(player, guard, arrestOptions(t), labels(player, t), line);
    }

    private static List<String> arrestOptions(Talk t) {
        List<String> options = new ArrayList<>();
        if (t.broke) {
            options.add("law_later");
            options.add("law_fight");
            return options;
        }
        if (t.deny) options.add(t.denies == 0 ? "law_deny" : "law_insist");
        if (t.slide) options.add("law_slide");
        if (t.bribe) options.add("law_bribe");
        options.add("law_pay");
        if (t.bribe) options.add("law_decline");
        options.add("law_fight");
        return options;
    }

    /** Wordings for this conversation's choices, picked at random (bribe and fine show their price). */
    private static Map<String, Component> labels(ServerPlayer player, Talk t) {
        Map<String, Component> labels = new LinkedHashMap<>();
        int bribe = bribeCost(t);
        for (String id : List.of("law_deny", "law_insist", "law_slide", "law_bribe", "law_pay", "law_decline", "law_fight",
                "law_later", "law_own_up", "law_double_down", "banter_pumped", "banter_humble", "banter_joke")) {
            Object arg = id.equals("law_bribe") ? bribe : t.fine;
            Component text = Component.translatable("villagechatter.option." + id + "." + player.getRandom().nextInt(3), arg);
            // Speech checks show their odds (api.SpeechCheck).
            if (id.equals("law_deny") || id.equals("law_insist")) {
                text = dev.villagechatter.api.SpeechCheck.label(dev.villagechatter.api.SpeechCheck.Skill.PERSUADE,
                        denyChance(player, t), text);
            } else if (id.equals("law_slide")) {
                text = dev.villagechatter.api.SpeechCheck.label(dev.villagechatter.api.SpeechCheck.Skill.PERSUADE, slideChance(player), text);
            } else if (id.equals("law_bribe")) {
                text = dev.villagechatter.api.SpeechCheck.label(dev.villagechatter.api.SpeechCheck.Skill.BRIBE, BRIBE, text);
            }
            labels.put(id, text);
        }
        return labels;
    }

    /** Talking your way out goes better when you look the part (api.SpeechCheck charisma). */
    private static double denyChance(ServerPlayer player, Talk t) {
        return dev.villagechatter.api.SpeechCheck.adjust(dev.villagechatter.api.SpeechCheck.Skill.PERSUADE,
                t.denies == 0 ? DENY_FIRST : DENY_AGAIN, player);
    }

    private static double slideChance(ServerPlayer player) {
        return dev.villagechatter.api.SpeechCheck.adjust(dev.villagechatter.api.SpeechCheck.Skill.PERSUADE, SLIDE, player);
    }

    private static int bribeCost(Talk t) {
        return Math.max(1, (int) Math.ceil(t.fine / 3.0));
    }

    public static boolean owns(ServerPlayer player, LivingEntity speaker) {
        Talk t = TALKS.get(player.getUUID());
        return t != null && t.speaker == speaker;
    }

    public static void onAction(ServerPlayer player, LivingEntity speaker, String action) {
        Talk t = TALKS.get(player.getUUID());
        if (t == null) return;
        switch (t.kind) {
            case ARREST -> arrest(player, t, action);
            case FRIEND -> friend(player, t, action);
            case BANTER -> banter(player, t, action);
            case COMPANION -> {
                Component reply = Companion.answer(player, (Mob) t.speaker, action);
                if (reply == null) TALKS.remove(player.getUUID());
                else finish(player, t, reply);
            }
        }
    }

    /** Busy with the law already (an arrest, a friend's word, a guard on the way)? */
    static boolean busy(ServerPlayer player) {
        return TALKS.containsKey(player.getUUID()) || WALKS.containsKey(player.getUUID());
    }

    /** Your hired guard wants a word (life.law.Companion). */
    static void companionTalk(ServerPlayer player, Mob guard, String key, List<String> options) {
        Talk t = new Talk(TalkKind.COMPANION, guard, null, List.of());
        TALKS.put(player.getUUID(), t);
        guard.getNavigation().stop();
        ConversationManager.openScripted(player, guard, options, companionLabels(player),
                Component.translatable("villagechatter.law.companion." + key + "." + player.getRandom().nextInt(3)));
    }

    private static Map<String, Component> companionLabels(ServerPlayer player) {
        Map<String, Component> labels = new LinkedHashMap<>();
        for (String id : List.of("comp_excuse", "comp_sorry", "comp_plead")) {
            labels.put(id, Component.translatable("villagechatter.option." + id + "." + player.getRandom().nextInt(3)));
        }
        return labels;
    }

    /** The conversation ended without a choice: walking off (or Esc, or logging out) is resisting arrest. */
    public static void walkedOff(ServerPlayer player, LivingEntity speaker) {
        Talk t = TALKS.get(player.getUUID());
        if (t == null || t.speaker != speaker) return;
        switch (t.kind) {
            case ARREST -> resist(player, t);
            case FRIEND -> doubleDown(player, t);
            case BANTER -> TALKS.remove(player.getUUID());
            case COMPANION -> {
                TALKS.remove(player.getUUID());
                Companion.answer(player, (Mob) t.speaker, DialogueAction.GOODBYE);
            }
        }
    }

    private static void arrest(ServerPlayer player, Talk t, String action) {
        var random = player.getRandom();
        switch (action) {
            case DialogueAction.GOODBYE, "law_fight" -> resist(player, t);
            case "law_deny", "law_insist" -> {
                if (!t.deny) return;
                double chance = denyChance(player, t);
                t.denies++;
                if (dev.villagechatter.life.Speech.roll(player, chance)) {
                    acquit(player, t, "deny_ok");
                    return;
                }
                failed(player, t);
                t.bribe = false; // you lied to him; now he won't take your money either
                t.slide = false;
                t.deny = t.denies < 2;
                reply(player, t, "deny_fail");
            }
            case "law_slide" -> {
                if (!t.slide) return;
                t.slide = false;
                if (dev.villagechatter.life.Speech.roll(player, slideChance(player))) {
                    acquit(player, t, "slide_ok");
                    return;
                }
                failed(player, t);
                t.bribe = false;
                t.deny = false;
                reply(player, t, "slide_fail");
            }
            case "law_bribe" -> {
                if (!t.bribe) return;
                t.bribe = false;
                int cost = bribeCost(t);
                if (emeralds(player) < cost) {
                    reply(player, t, "no_money");
                    return;
                }
                if (random.nextDouble() < BRIBE) {
                    takeEmeralds(player, cost);
                    acquit(player, t, "bribe_ok");
                    return;
                }
                failed(player, t);
                t.deny = false;
                t.slide = false;
                reply(player, t, "bribe_fail");
            }
            case "law_decline" -> {
                t.bribe = false;
                reply(player, t, "decline");
            }
            case "law_pay" -> {
                if (emeralds(player) < t.fine) {
                    t.broke = true;
                    reply(player, t, "cant_pay");
                    return;
                }
                takeEmeralds(player, t.fine);
                CrimeLedger.Standing s = CrimeLedger.get(player.server).standing(player.getUUID(), t.village);
                for (CrimeLedger.Crime c : t.crimes) {
                    Law.landHits(player, c, t.speaker);
                    if (returnStolen(player, c) && t.speaker instanceof Mob guard) {
                        PATROLS.add(new Patrol(guard, c.chest, player.serverLevel().getGameTime() + DAY));
                    }
                }
                s.crimes.removeAll(t.crimes);
                finish(player, t, "paid");
            }
            case "law_later" -> {
                // Can't pay: they'll find you again later.
                NEXT_ARREST.put(player.getUUID(), player.serverLevel().getGameTime() + 6000);
                finish(player, t, "later");
            }
            default -> { }
        }
    }

    private static void failed(ServerPlayer player, Talk t) {
        VillageStanding.get(player.serverLevel()).add(t.village.pos(), player.getUUID(), VillageStanding.Group.GUARDS, -FAIL_GUARDS);
        Reputation.addVillageGoodwill(player, t.speaker, -FAIL_VILLAGE);
    }

    /**
     * Talked out of it: the village never hears of it (whoever saw it still remembers). The guards think a little
     * less of you for it anyway: a bribe more than a smooth word.
     */
    private static void acquit(ServerPlayer player, Talk t, String key) {
        CrimeLedger.get(player.server).standing(player.getUUID(), t.village).crimes.removeAll(t.crimes);
        VillageStanding.get(player.serverLevel()).add(t.village.pos(), player.getUUID(), VillageStanding.Group.GUARDS,
                key.equals("bribe_ok") ? -2 : -1.5);
        finish(player, t, key);
    }

    private static void resist(ServerPlayer player, Talk t) {
        TALKS.remove(player.getUUID());
        CrimeLedger.Standing s = CrimeLedger.get(player.server).standing(player.getUUID(), t.village);
        VillageStanding.get(player.serverLevel()).add(t.village.pos(), player.getUUID(), VillageStanding.Group.GUARDS, -RESIST_GUARDS);
        Reputation.addVillageGoodwill(player, t.speaker, -RESIST_VILLAGE);
        turnHostile(player, s, t.speaker);
        ConversationManager.endScripted(player);
        LivingVillage.say(player, t.speaker, line(player, "resist"));
    }

    private static void reply(ServerPlayer player, Talk t, String key) {
        ConversationManager.scriptedLine(player, t.speaker, arrestOptions(t), labels(player, t), line(player, key));
    }

    private static void finish(ServerPlayer player, Talk t, String key) {
        finish(player, t, line(player, key));
    }

    private static void finish(ServerPlayer player, Talk t, Component line) {
        TALKS.remove(player.getUUID());
        NEXT_ARREST.put(player.getUUID(), player.serverLevel().getGameTime() + 1200);
        ConversationManager.scriptedLine(player, t.speaker, List.of(DialogueAction.GOODBYE), Map.of(), line);
    }

    /** Pools with more than the usual three lines. */
    private static final Map<String, Integer> LINE_COUNTS = Map.of("bribe_ok", 5, "paid", 5, "accost.theft", 7, "friend.theft", 6);

    private static Component line(ServerPlayer player, String key) {
        return Component.translatable("villagechatter.law." + key + "." + player.getRandom().nextInt(LINE_COUNTS.getOrDefault(key, 3)));
    }

    // ------------------------------------------------------------------ emeralds and stolen goods

    private static int emeralds(ServerPlayer player) {
        return player.getInventory().countItem(Items.EMERALD);
    }

    private static void takeEmeralds(ServerPlayer player, int count) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(count, s.getCount());
            s.shrink(take);
            count -= take;
        }
        inv.setChanged();
    }

    /** Takes back what was stolen (as much as you still have) and puts it in the chest it came from. */
    private static boolean returnStolen(ServerPlayer player, CrimeLedger.Crime c) {
        if (c.stolen.isEmpty() || c.chest == null) return false;
        ServerLevel level = player.serverLevel();
        var inv = player.getInventory();
        Container chest = level.getBlockEntity(c.chest) instanceof Container box ? box : null;
        for (ItemStack wanted : c.stolen) {
            int left = wanted.getCount();
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!ItemStack.isSameItemSameComponents(s, wanted)) continue;
                int take = Math.min(left, s.getCount());
                ItemStack back = s.split(take);
                left -= take;
                ItemStack rest = chest == null ? back : addTo(chest, back);
                if (!rest.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(level, c.chest.getX() + 0.5, c.chest.getY() + 1, c.chest.getZ() + 0.5, rest));
                }
            }
        }
        inv.setChanged();
        if (chest != null) chest.setChanged();
        c.stolen.clear();
        return true;
    }

    private static ItemStack addTo(Container box, ItemStack stack) {
        for (int i = 0; i < box.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack in = box.getItem(i);
            if (in.isEmpty()) {
                box.setItem(i, stack);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), in.getMaxStackSize() - in.getCount());
                in.grow(move);
                stack.shrink(move);
            }
        }
        return stack;
    }

    // ------------------------------------------------------------------ a friend's word

    private static void openFriend(ServerPlayer player, Villager friend, CrimeLedger.Crime crime) {
        Talk t = new Talk(TalkKind.FRIEND, friend, crime.village, new ArrayList<>(List.of(crime)));
        TALKS.put(player.getUUID(), t);
        friend.getNavigation().stop();
        LivingVillage.babble(friend, 4, 0, -0.1F, 0.9F);
        String key = "friend." + (crime.kind == CrimeKind.THEFT ? "theft" : crime.kind == CrimeKind.BED ? "bed" : "lead");
        ConversationManager.openScripted(player, friend, List.of("law_own_up", "law_double_down"), labels(player, t), line(player, key));
    }

    private static void friend(ServerPlayer player, Talk t, String action) {
        if (!action.equals("law_own_up")) {
            doubleDown(player, t);
            return;
        }
        CrimeLedger.Crime crime = t.crimes.get(0);
        CrimeLedger.Standing s = CrimeLedger.get(player.server).standing(player.getUUID(), t.village);
        s.crimes.remove(crime);
        s.forgivenUntil = player.serverLevel().getGameTime() + DAY;
        if (t.speaker instanceof Villager v) Reputation.adjust(player, v, (int) Math.round(crime.kind.victimHit * 0.25), false);
        returnStolen(player, crime);
        finish(player, t, "forgiven");
    }

    /** Stubborn about it: they go and tell a guard. */
    private static void doubleDown(ServerPlayer player, Talk t) {
        TALKS.remove(player.getUUID());
        CrimeLedger.Crime crime = t.crimes.get(0);
        ConversationManager.scriptedLine(player, t.speaker, List.of(DialogueAction.GOODBYE), Map.of(), line(player, "reporting"));
        tell(player, t.speaker, crime);
    }

    // ------------------------------------------------------------------ after a raid

    /** Hero of the Village means you just won a raid: a guard who saw it comes over ("We make a great team!"). */
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!Law.enabled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getEffectInstance().getEffect().is(MobEffects.HERO_OF_THE_VILLAGE)) return;
        long time = player.serverLevel().getGameTime();
        if (NEXT_BANTER.getOrDefault(player.getUUID(), 0L) > time) return;
        NEXT_BANTER.put(player.getUUID(), time + DAY);
        // Saving the village: a big boost with its golems and guards, and more with your own hired guard.
        Law.goodDeed(player, player, 5, 10);
        Companion.cheer(player, 6);
        LivingVillage.later(60, () -> {
            if (!player.isAlive() || TALKS.containsKey(player.getUUID())) return;
            for (Mob m : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                    m -> m.isAlive() && GuardTrust.isGuard(m) && m.getTarget() == null && m.hasLineOfSight(player))) {
                Optional<GlobalPos> village = Witnesses.village(player.serverLevel(), m.blockPosition());
                if (village.isEmpty()) return;
                Talk t = new Talk(TalkKind.BANTER, m, village.get(), List.of());
                TALKS.put(player.getUUID(), t);
                m.getNavigation().moveTo(player, 1.0);
                ConversationManager.openScripted(player, m, List.of("banter_pumped", "banter_humble", "banter_joke"),
                        labels(player, t), line(player, "banter.raid"));
                return;
            }
        });
    }

    private static void banter(ServerPlayer player, Talk t, String action) {
        if (action.equals("banter_pumped") || action.equals("banter_humble")) {
            // Your own hired guard: a shared victory means a lot more.
            double boost = Companion.isHiredBy(t.speaker, player) ? 8.0 : 3.0;
            player.getData(QuestRegistries.GOODWILL).points.merge(t.speaker.getUUID(), boost, Double::sum);
            Reputation.addVillageGoodwill(player, t.speaker, 1);
            finish(player, t, action.equals("banter_humble") ? "banter.humble" : "banter.pumped");
        } else if (action.equals("banter_joke")) {
            finish(player, t, "banter.joke");
        } else {
            TALKS.remove(player.getUUID());
        }
    }

    // ------------------------------------------------------------------ village animals and the kid's pet

    /** Animals born in a village corral belong to the village (taking them off on a lead is theft). */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Animal animal) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (animal.getTags().contains("villagechatter:checked")) return;
        animal.addTag("villagechatter:checked");
        LivingVillage.later(20, () -> {
            if (!animal.isAlive() || (animal instanceof AbstractHorse h && h.isTamed())) return;
            if (Witnesses.village(level, animal.blockPosition()).isEmpty() || !fencedIn(level, animal.blockPosition())) return;
            animal.addTag(Witnesses.VILLAGE_ANIMAL);
        });
    }

    /** Fence, wall or gate within 12 blocks in all four directions. */
    private static boolean fencedIn(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int i = 1; i <= 12 && !hit; i++) {
                BlockPos p = pos.relative(d, i);
                hit = fence(level.getBlockState(p)) || fence(level.getBlockState(p.above()));
            }
            if (!hit) return false;
        }
        return true;
    }

    private static boolean fence(BlockState state) {
        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES);
    }

    public static final String KID_OWNER = "villagechatter:kid_owner";

    /** Taming a kid's pet (one you brought back for them) is stealing it. */
    public static void onTame(AnimalTameEvent event) {
        if (!(event.getTamer() instanceof ServerPlayer player)) return;
        var data = event.getAnimal().getPersistentData();
        UUID kidId;
        try {
            // villagequests stores the kid's UUID as a string; an int-array UUID works too
            kidId = data.hasUUID(KID_OWNER) ? data.getUUID(KID_OWNER)
                    : data.contains(KID_OWNER, net.minecraft.nbt.Tag.TAG_STRING) ? UUID.fromString(data.getString(KID_OWNER)) : null;
        } catch (IllegalArgumentException bad) {
            kidId = null;
        }
        if (kidId == null) return;
        if (player.serverLevel().getEntity(kidId) instanceof Villager kid) {
            Reputation.adjust(player, kid, -6, false);
            LivingVillage.say(player, kid, line(player, "stolen_pet"));
        }
        event.getAnimal().getPersistentData().remove(KID_OWNER);
    }
}

package dev.villagechatter.reputation.faction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * What piglins and pillagers hold against you, and what they like - all through {@link Witnessed}, so a wrong only
 * counts once a witness tells another of them.
 * <ul>
 *   <li>Piglins: mining their gold, opening their chests, hitting one, killing one. Killing their enemies (wither
 *   skeletons, the wither) in front of them pleases them. Brutes don't count: they attack everyone anyway.</li>
 *   <li>Pillagers: hitting or killing one, and taking from an outpost's chests unless you're their ally.</li>
 *   <li>Piglins who like you enough step in when something attacks you.</li>
 * </ul>
 */
public final class FactionDeeds {
    private FactionDeeds() {}

    /** "player|victim" -> game time of the last hit that counted (one wrong per beating, not per swing). */
    private static final Map<String, Long> HITS = new HashMap<>();
    private static final long HIT_COOLDOWN = 200;
    /** Player -> chest they opened inside an outpost, and how full it was. */
    private static final Map<UUID, Opened> OUTPOST = new HashMap<>();
    private static final Map<UUID, BlockPos> CLICKED = new HashMap<>();

    private record Opened(BlockPos pos, int items) {}

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    // ------------------------------------------------------------------ piglins

    /** Mining gold piglins guard. */
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !event.getState().is(BlockTags.GUARDED_BY_PIGLINS)) return;
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof Container) return; // chests: counted on opening
        Witnessed.offense(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_MINE_LOSS.get(), Vec3.atCenterOf(event.getPos()), null);
    }

    /** Opening a chest piglins guard. Also remembers outpost chests for the pillager side. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        var state = player.level().getBlockState(pos);
        if (!(player.level().getBlockEntity(pos) instanceof Container)) return;
        CLICKED.put(player.getUUID(), pos.immutable());
        if (state.is(BlockTags.GUARDED_BY_PIGLINS)) {
            String key = player.getUUID() + "|" + pos.asLong();
            Long last = HITS.get(key);
            if (last != null && now(player) - last < HIT_COOLDOWN * 3) return;
            HITS.put(key, now(player));
            Witnessed.offense(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_CHEST_LOSS.get(), Vec3.atCenterOf(pos), null);
        }
    }

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        ServerPlayer player = dev.villagechatter.life.law.Blame.culprit(event.getSource());
        if (player != null) {
            if (dev.villagechatter.life.FriendlyFire.forgiven(player, victim)) return;
            Factions.Faction faction = victim instanceof Piglin ? Factions.Faction.PIGLIN
                    : victim instanceof AbstractIllager ? Factions.Faction.PILLAGER : null;
            if (faction == null) return;
            String key = player.getUUID() + "|" + victim.getUUID();
            Long last = HITS.get(key);
            if (last != null && now(player) - last < HIT_COOLDOWN) return;
            HITS.put(key, now(player));
            if (HITS.size() > 512) HITS.clear();
            double loss = faction == Factions.Faction.PIGLIN ? ChatterConfig.PIGLIN_HURT_LOSS.get() : ChatterConfig.PILLAGER_HURT_LOSS.get();
            Witnessed.offense(player, faction, loss, victim.position(), victim);
            return;
        }
        // Something attacks a player the piglins like: they step in.
        if (victim instanceof ServerPlayer target && event.getSource().getEntity() instanceof Mob attacker && !(attacker instanceof AbstractPiglin)) {
            help(target, attacker);
        }
    }

    private static void help(ServerPlayer player, Mob attacker) {
        if (!FactionBook.enabled() || FactionBook.piglin(player) < ChatterConfig.PIGLIN_HELP_AT.get()) return;
        for (Piglin p : player.serverLevel().getEntitiesOfClass(Piglin.class, new AABB(player.blockPosition()).inflate(16),
                p -> p.isAlive() && !p.isBaby() && p.getTarget() == null && p.hasLineOfSight(attacker))) {
            p.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, attacker);
            p.setTarget(attacker);
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;
        ServerPlayer player = dev.villagechatter.life.law.Blame.culprit(event.getSource());
        if (player == null || player.isCreative()) return;
        if (dead instanceof Piglin) {
            Witnessed.offense(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_KILL_LOSS.get(), dead.position(), dead);
        } else if (dead instanceof WitherBoss) {
            Witnessed.deed(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_WITHER_GAIN.get(), dead.position());
        } else if (dead instanceof WitherSkeleton) {
            Witnessed.deed(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_ENEMY_GAIN.get(), dead.position());
        }
    }

    // ------------------------------------------------------------------ pillager outposts

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = CLICKED.remove(player.getUUID());
        if (pos == null || !inOutpost(player.serverLevel(), pos)) return;
        OUTPOST.put(player.getUUID(), new Opened(pos, count(event.getContainer())));
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Opened opened = OUTPOST.remove(player.getUUID());
        if (opened == null || opened.items() - count(event.getContainer()) <= 0) return;
        if (FactionBook.tier(FactionBook.pillagerSeen(player)) == Factions.Tier.ALLY) return; // allies help themselves
        Witnessed.offense(player, Factions.Faction.PILLAGER, ChatterConfig.PILLAGER_CHEST_LOSS.get(), Vec3.atCenterOf(opened.pos()), null);
    }

    static boolean inOutpost(ServerLevel level, BlockPos pos) {
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var outpost = registry.get(BuiltinStructures.PILLAGER_OUTPOST);
        return outpost != null && level.structureManager().getStructureWithPieceAt(pos, outpost).isValid();
    }

    private static int count(AbstractContainerMenu menu) {
        int n = 0;
        for (var slot : menu.slots) if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) n += slot.getItem().getCount();
        return n;
    }

    public static void onLogout(Player player) {
        OUTPOST.remove(player.getUUID());
        CLICKED.remove(player.getUUID());
    }
}

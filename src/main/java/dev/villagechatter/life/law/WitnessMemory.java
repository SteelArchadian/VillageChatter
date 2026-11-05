package dev.villagechatter.life.law;

import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Each crime has its own day clock (config), counted from when it happened. Inside the day the witnesses' own village
 * reacts as usual (guards, the chase), but nothing spreads: not to the village faction, not to other villages. Kill
 * every witness inside the day and the crime is gone. Once the day is out with any witness alive, it's locked in, and
 * killing the last of them changes nothing (the killing is a crime of its own if anyone sees it).
 */
public final class WitnessMemory {
    private WitnessMemory() {}

    private static final int TICK_EVERY = 100;
    /** Share of a deferred crime's hit that lands on the village, and on the faction's word on the road. */
    private static final double VILLAGE_SHARE = 0.25, FACTION_SHARE = 0.25;

    public static boolean pending(CrimeLedger.Crime crime, long now) {
        return now - crime.time < ChatterConfig.WITNESS_MEMORY_TICKS.get();
    }

    /** True while this player has a crime at this village whose day isn't out: the village's news stays home. */
    public static boolean hasPending(MinecraftServer server, UUID player, GlobalPos village) {
        CrimeLedger.Standing s = CrimeLedger.get(server).all(player).get(village);
        if (s == null) return false;
        long now = server.overworld().getGameTime();
        return s.crimes.stream().anyMatch(c -> !c.witnesses.isEmpty() && pending(c, now));
    }

    static void died(LivingEntity witness) {
        if (!Law.enabled() || !(witness.level() instanceof ServerLevel level)) return;
        forget(level, witness.getUUID());
    }

    /** This witness no longer remembers anything inside its day; crimes nobody else knows of are dropped. */
    static void forget(ServerLevel level, UUID witness) {
        CrimeLedger ledger = CrimeLedger.get(level.getServer());
        long now = level.getServer().overworld().getGameTime();
        for (Object[] seen : ledger.seenBy(witness)) {
            CrimeLedger.Standing standing = (CrimeLedger.Standing) seen[1];
            CrimeLedger.Crime crime = (CrimeLedger.Crime) seen[2];
            if (!pending(crime, now)) continue;
            crime.witnesses.remove(witness);
            if (crime.witnesses.isEmpty()) standing.crimes.remove(crime);
            ledger.setDirty();
        }
    }

    /** Deferred crimes whose day is out: they land (a witness lived) or were already dropped (none did). */
    public static void tick(MinecraftServer server, int tick) {
        if (tick % TICK_EVERY != 0 || !Law.enabled()) return;
        CrimeLedger ledger = CrimeLedger.get(server);
        long now = server.overworld().getGameTime();
        for (Object[] row : ledger.everything()) {
            UUID id = (UUID) row[0];
            GlobalPos village = (GlobalPos) row[1];
            CrimeLedger.Standing standing = (CrimeLedger.Standing) row[2];
            for (CrimeLedger.Crime crime : java.util.List.copyOf(standing.crimes)) {
                if (!crime.deferred || pending(crime, now)) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) continue; // lands when they're back
                land(player, server, village, crime);
                ledger.setDirty();
            }
        }
    }

    /** The news arrives: the village thinks less of you, word spreads on the road, and its guards know. */
    static void land(ServerPlayer player, MinecraftServer server, GlobalPos village, CrimeLedger.Crime crime) {
        crime.deferred = false;
        crime.reported = true;
        ServerLevel level = server.getLevel(village.dimension());
        if (level != null) VillageStanding.get(level).add(village.pos(), player.getUUID(), VillageStanding.Group.ADVOCACY,
                -crime.kind.victimHit * VILLAGE_SHARE);
        FactionBook.adjust(player, Factions.Faction.VILLAGE, -crime.kind.victimHit * FACTION_SHARE);
    }
}

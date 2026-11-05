package dev.villagechatter.api;

import java.util.Collection;
import java.util.UUID;

import dev.villagechatter.life.law.CrimeKind;
import dev.villagechatter.life.law.CrimeLedger;
import dev.villagechatter.life.law.Law;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Crimes from other mods (Encounters' caravans). The crime gets the same day clock as any other: it reaches the
 * village and the village faction only once the configured delay (witnessMemoryTicks, a Minecraft day by default) is
 * out, and only if one of the witnesses is still alive then. Kill them all inside the day and it never happened.
 */
public final class Crimes {
    private Crimes() {}

    /**
     * Report a crime to a village, landing after the delay.
     * @param player who did it
     * @param kind what they did (e.g. {@link CrimeKind#THEFT}, {@link CrimeKind#ASSAULT_ANIMAL})
     * @param village the village it's reported to (its anchor bell), e.g. the caravan's destination
     * @param witnesses the entities who saw it (UUIDs); their deaths are tracked like any witness's
     * @return false if the law is off, the player is in creative/spectator, or nobody saw it
     */
    public static boolean reportLater(ServerPlayer player, CrimeKind kind, GlobalPos village, Collection<UUID> witnesses) {
        if (!Law.enabled() || player.isCreative() || player.isSpectator() || witnesses.isEmpty()) return false;
        CrimeLedger ledger = CrimeLedger.get(player.server);
        CrimeLedger.Crime crime = new CrimeLedger.Crime(kind, village, null, player.server.overworld().getGameTime());
        crime.deferred = true;
        crime.fine = kind.rollFine(player.getRandom());
        for (UUID w : witnesses) crime.witnesses.put(w, 0);
        ledger.standing(player.getUUID(), village).crimes.add(crime);
        ledger.setDirty();
        return true;
    }

    /** The bounty on a player: the fines of every crime reported against them, across all villages. */
    public static int bounty(ServerPlayer player) {
        int sum = 0;
        for (CrimeLedger.Standing s : CrimeLedger.get(player.server).all(player.getUUID()).values()) {
            for (CrimeLedger.Crime c : s.crimes) if (c.reported) sum += c.fine;
        }
        return sum;
    }

    /** The bounty is paid (a collector took it): reported crimes are cleared and no village is hostile any more. */
    public static void payBounty(ServerPlayer player) {
        CrimeLedger ledger = CrimeLedger.get(player.server);
        for (CrimeLedger.Standing s : ledger.all(player.getUUID()).values()) {
            s.crimes.removeIf(c -> c.reported);
            s.hostileUntil = 0;
        }
        ledger.setDirty();
    }

    /** The village (its anchor bell) with the biggest reported fine against the player, or null if none. */
    public static net.minecraft.core.BlockPos worstVillage(ServerPlayer player) {
        GlobalPos worst = null;
        int most = 0;
        for (var e : CrimeLedger.get(player.server).all(player.getUUID()).entrySet()) {
            int sum = 0;
            for (CrimeLedger.Crime c : e.getValue().crimes) if (c.reported) sum += c.fine;
            if (sum > most) {
                most = sum;
                worst = e.getKey();
            }
        }
        return worst == null ? null : worst.pos();
    }
}

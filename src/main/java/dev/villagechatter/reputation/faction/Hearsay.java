package dev.villagechatter.reputation.faction;

import java.util.Optional;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.law.CrimeLedger;
import dev.villagechatter.life.law.Witnesses;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * "They've heard about you": crimes that landed on a village before you ever set foot in it (a caravan robbed on its
 * way here, say) come up when its people and guards greet you.
 */
public final class Hearsay {
    private Hearsay() {}

    private static final double CHANCE = 0.6;
    private static final int VARIANTS = 5;

    /** The worst crime this village heard about before you first came, if any. */
    static Optional<CrimeLedger.Crime> heard(ServerPlayer player, GlobalPos village) {
        CrimeLedger.Standing standing = CrimeLedger.get(player.server).all(player.getUUID()).get(village);
        if (standing == null || standing.crimes.isEmpty()) return Optional.empty();
        // Been there before factions kept track (VISITED but no first-seen): nothing they know is hearsay.
        boolean visited = dev.villagechatter.life.law.VillageStanding.get(player.serverLevel())
                .get(village.pos(), player.getUUID(), dev.villagechatter.life.law.VillageStanding.Group.VISITED) > 0;
        long first = FactionBook.firstSeen(player, village).orElse(visited ? Long.MIN_VALUE : Long.MAX_VALUE);
        long now = player.server.overworld().getGameTime();
        return standing.crimes.stream().filter(c -> c.time < first && !c.deferred
                        && !dev.villagechatter.life.law.WitnessMemory.pending(c, now))
                .max(java.util.Comparator.comparingDouble(c -> c.kind.victimHit));
    }

    /** A greeting that mentions what they heard, or empty (most of the time when there's something to mention). */
    public static Optional<Component> greeting(ServerPlayer player, LivingEntity speaker) {
        if (!FactionBook.enabled()) return Optional.empty();
        Optional<GlobalPos> village = Witnesses.village(player.serverLevel(), speaker.blockPosition());
        if (village.isEmpty()) return Optional.empty();
        Optional<CrimeLedger.Crime> crime = heard(player, village.get());
        if (crime.isEmpty() || speaker.getRandom().nextDouble() >= CHANCE) return Optional.empty();
        String who = GuardTrust.isGuard(speaker) ? "guard" : "villager";
        String how = crime.get().kind.murder ? "serious" : "petty";
        return Optional.of(Component.translatable("villagechatter.heard." + who + "." + how + "." + speaker.getRandom().nextInt(VARIANTS),
                player.getDisplayName()));
    }
}

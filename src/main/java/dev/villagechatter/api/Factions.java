package dev.villagechatter.api;

import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.server.level.ServerPlayer;

/**
 * Village Chatter's faction standings, for other mods (Encounters, Tactics). All three meters run from -100 to 100.
 * Call from the server thread. With factions switched off in the config, adjust does nothing and the readings stay
 * at their starting values.
 *
 * <ul>
 *   <li>{@link #village}: villages as a whole. A summary of every village you know (weighted by size and how recently
 *   you saw it) plus a "word on the road" term that fades toward 0 (where {@code adjust(VILLAGE, ...)} lands).</li>
 *   <li>{@link #pillager}: the pillagers' own meter, starting hostile (-50 by default).</li>
 *   <li>{@link #piglin}: the piglins' meter, starting neutral.</li>
 * </ul>
 */
public final class Factions {
    private Factions() {}

    public enum Faction { VILLAGE, PILLAGER, PIGLIN }

    /**
     * How pillagers treat you. HATED: attack on sight. WARY: talk first, tolls. TOLERATED: let you pass (drawing a
     * weapon gets a warning). ALLY: highwaymen wave you through, outposts let you in.
     */
    public enum Tier { HATED, WARY, TOLERATED, ALLY }

    public static double village(ServerPlayer player) {
        return FactionBook.village(player);
    }

    public static double pillager(ServerPlayer player) {
        return FactionBook.pillager(player);
    }

    /**
     * The piglins' meter: it rises with barters and falls when you anger or kill piglins. High standing gets friendlier
     * talk (and, if the config allows, a small barter bonus); low standing, a shorter fuse.
     */
    public static double piglin(ServerPlayer player) {
        return FactionBook.piglin(player);
    }

    /** The player's pillager tier, as pillagers see it (an ominous banner disguise shows them Uneasy). */
    public static Tier tier(ServerPlayer player) {
        return FactionBook.tier(FactionBook.pillagerSeen(player));
    }

    /** True when villages hate you enough that guards (and caravan guards) attack on sight. */
    public static boolean villageHostile(ServerPlayer player) {
        return FactionBook.enabled() && FactionBook.village(player) < dev.villagechatter.ChatterConfig.VILLAGE_HOSTILE_BELOW.get();
    }

    /**
     * A greeting for a pillager (or highwayman) to say to this player, by both standings: a village hero they hate,
     * a village hero who's also their friend, a plain friend, or an enemy. Variants, with a nod or two.
     */
    public static net.minecraft.network.chat.Component pillagerGreeting(ServerPlayer player) {
        return dev.villagechatter.reputation.faction.Allegiance.pillagerGreeting(player);
    }

    /**
     * Move one meter. Paying a toll or doing pillagers a favor: {@code adjust(p, PILLAGER, +n)}. A caravan crime
     * away from any village: {@code adjust(p, VILLAGE, -n)} (it fades over time). Village standing only drops when
     * someone actually saw it (a villager, guard, golem or caravan witness): don't lower VILLAGE for unwitnessed deeds.
     */
    public static void adjust(ServerPlayer player, Faction faction, double amount) {
        FactionBook.adjust(player, faction, amount);
    }
}

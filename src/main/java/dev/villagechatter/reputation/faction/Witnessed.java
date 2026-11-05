package dev.villagechatter.reputation.faction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * How word travels among piglins and pillagers (not villages: those have life.law). A wrong only counts once a
 * witness has told one other member (two who saw it tell each other):
 * <ul>
 *   <li>Nobody saw it, or the only witnesses die before telling anyone: it never happened.</li>
 *   <li>A witness tells someone: the loss lands (a flat amount, however many saw it).</li>
 *   <li>For a day after that the secret can still die with them: kill every one who knows (the witnesses and the one
 *   they told) and the loss is undone. Those killings are wrongs of their own if anyone else sees them. After the
 *   day, it's for good. A witness you let live who found nobody to tell within the day gets the word out anyway:
 *   after the day, it lands.</li>
 * </ul>
 * Piglins: piglins (not brutes, who attack everyone anyway). Pillagers: illagers. Good deeds count at once if one of
 * them saw it: good news needs no confidant. Pending wrongs are saved with the player.
 */
public final class Witnessed {
    private Witnessed() {}

    public static final long DAY = 24000;
    private static final double SIGHT = 16, TELL_RANGE = 12;
    /** Players with something pending who aren't in the player list (fake players: tests, machines). */
    private static final java.util.Set<ServerPlayer> OTHERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static List<ServerPlayer> players(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>(server.getPlayerList().getPlayers());
        OTHERS.removeIf(p -> p.isRemoved() || p.getData(ChatterAttachments.OFFENSES).isEmpty());
        for (ServerPlayer p : OTHERS) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** A wrong seen by members of a faction: who saw it, who knows now, and whether it has landed. */
    public record Offense(Factions.Faction faction, double amount, List<UUID> witnesses, List<UUID> knowers, boolean told, long at) {
        static final Codec<Offense> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.xmap(Factions.Faction::valueOf, Factions.Faction::name).fieldOf("faction").forGetter(Offense::faction),
                Codec.DOUBLE.fieldOf("amount").forGetter(Offense::amount),
                UUIDUtil.STRING_CODEC.listOf().fieldOf("witnesses").forGetter(Offense::witnesses),
                UUIDUtil.STRING_CODEC.listOf().fieldOf("knowers").forGetter(Offense::knowers),
                Codec.BOOL.fieldOf("told").forGetter(Offense::told),
                Codec.LONG.fieldOf("at").forGetter(Offense::at)).apply(i, Offense::new));
        public static final Codec<List<Offense>> LIST = CODEC.listOf();
    }

    /** Members of a faction who'd see and tell: piglins (no brutes), illagers. */
    public static boolean member(Entity e, Factions.Faction faction) {
        if (!(e instanceof LivingEntity l) || !l.isAlive()) return false;
        return faction == Factions.Faction.PIGLIN ? e instanceof Piglin : faction == Factions.Faction.PILLAGER && e instanceof AbstractIllager;
    }

    /** Members who can see the player (or the spot it happened), the victim included if it lived. */
    public static List<UUID> witnesses(ServerPlayer player, Factions.Faction faction, Vec3 where, Entity victim) {
        List<UUID> out = new ArrayList<>();
        ServerLevel level = player.serverLevel();
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(where, where).inflate(SIGHT), m -> member(m, faction))) {
            if (m == victim && !m.isAlive()) continue;
            if (m == victim || m.hasLineOfSight(player)) out.add(m.getUUID());
        }
        return out;
    }

    /** A wrong: lands only once a witness tells another member. */
    public static void offense(ServerPlayer player, Factions.Faction faction, double amount, Vec3 where, Entity victim) {
        if (amount <= 0 || !FactionBook.enabled() || player.isCreative()) return;
        List<UUID> seen = witnesses(player, faction, where, victim);
        if (seen.isEmpty()) return;
        List<Offense> list = new ArrayList<>(player.getData(ChatterAttachments.OFFENSES));
        list.add(new Offense(faction, amount, seen, List.of(), false, player.server.overworld().getGameTime()));
        player.setData(ChatterAttachments.OFFENSES, list);
        if (!server(player).getPlayerList().getPlayers().contains(player)) OTHERS.add(player);
    }

    /** A good deed: counts at once if a member saw it. @return true if it counted. */
    public static boolean deed(ServerPlayer player, Factions.Faction faction, double amount, Vec3 where) {
        if (amount <= 0 || !FactionBook.enabled()) return false;
        if (witnesses(player, faction, where, null).isEmpty()) return false;
        FactionBook.adjust(player, faction, amount);
        return true;
    }

    /** Once a second: witnesses tell someone; old secrets set or fade. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : players(server)) {
            List<Offense> list = player.getData(ChatterAttachments.OFFENSES);
            if (list.isEmpty()) continue;
            List<Offense> next = new ArrayList<>(list.size());
            boolean changed = false;
            for (Offense o : list) {
                if (now - o.at() >= DAY) { // told: it stays for good; a witness you let live tells eventually
                    if (!o.told()) FactionBook.adjust(player, o.faction(), -o.amount());
                    changed = true;
                    continue;
                }
                if (!o.told()) {
                    Offense t = tell(server, player, o);
                    if (t != o) changed = true;
                    next.add(t);
                } else next.add(o);
            }
            if (changed) player.setData(ChatterAttachments.OFFENSES, next);
        }
    }

    /** A loaded witness with another member in earshot (a fellow witness counts): tells them, and the loss lands. */
    private static Offense tell(MinecraftServer server, ServerPlayer player, Offense o) {
        for (ServerLevel level : server.getAllLevels()) {
            for (UUID id : o.witnesses()) {
                if (!(level.getEntity(id) instanceof Mob w) || !w.isAlive()) continue;
                for (Mob other : level.getEntitiesOfClass(Mob.class, w.getBoundingBox().inflate(TELL_RANGE),
                        m -> m != w && member(m, o.faction()))) {
                    List<UUID> knowers = new ArrayList<>(o.witnesses());
                    if (!knowers.contains(other.getUUID())) knowers.add(other.getUUID());
                    FactionBook.adjust(player, o.faction(), -o.amount());
                    return new Offense(o.faction(), o.amount(), o.witnesses(), knowers, true, o.at());
                }
            }
        }
        return o;
    }

    /**
     * A member died: one fewer who can tell. Untold and no witness left: never happened. Told and nobody left who
     * knows, within the day: the loss is undone.
     */
    public static void onDeath(LivingDeathEvent event) {
        Entity dead = event.getEntity();
        if (dead.level().isClientSide() || !(dead instanceof AbstractIllager || dead instanceof Piglin)) return;
        UUID id = dead.getUUID();
        MinecraftServer server = dead.getServer();
        if (server == null) return;
        for (ServerPlayer player : players(server)) {
            List<Offense> list = player.getData(ChatterAttachments.OFFENSES);
            if (list.isEmpty()) continue;
            List<Offense> next = new ArrayList<>(list.size());
            boolean changed = false;
            for (Offense o : list) {
                if (!o.witnesses().contains(id) && !o.knowers().contains(id)) {
                    next.add(o);
                    continue;
                }
                changed = true;
                List<UUID> w = new ArrayList<>(o.witnesses());
                w.remove(id);
                List<UUID> k = new ArrayList<>(o.knowers());
                k.remove(id);
                if (!o.told() && w.isEmpty()) continue; // nobody left to tell
                if (o.told() && k.isEmpty()) { // the secret died with them
                    FactionBook.adjust(player, o.faction(), o.amount());
                    continue;
                }
                next.add(new Offense(o.faction(), o.amount(), w, k, o.told(), o.at()));
            }
            if (changed) player.setData(ChatterAttachments.OFFENSES, next);
        }
    }

    private static MinecraftServer server(ServerPlayer player) {
        return player.server;
    }

    /** For tests and the journal: wrongs still waiting to be told, or still undoable. */
    public static List<Offense> pending(ServerPlayer player) {
        return player.getData(ChatterAttachments.OFFENSES);
    }
}

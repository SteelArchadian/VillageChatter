package dev.villagechatter.life.law;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every player's open crimes and standing with the law, per village. Saved once, with the overworld. A crime stays
 * open until it's settled in an arrest (paid, talked down or fought) or forgotten after a few days.
 */
public final class CrimeLedger extends SavedData {

    /** One crime. The village and guard hits are held back until it's settled: talk your way out and they never land. */
    public static final class Crime {
        public final CrimeKind kind;
        public final GlobalPos village;
        public final UUID victim;
        public final long time;
        /** A guard saw it himself: no denying it. */
        public boolean seenByGuard;
        /** A witness told a guard or golem (or a guard saw it). */
        public boolean reported;
        /** Only a golem knows: it watches you rather than sending a guard. */
        public boolean toGolem;
        public int fine, hits = 1;
        public double villageHit, guardHit;
        public final List<ItemStack> stolen = new ArrayList<>();
        public BlockPos chest;
        /**
         * Who saw it, and how much each one's own opinion of you dropped. Until the crime is reported or a day has
         * passed (config), a witness's memory is pending: if they all die first, it's as if it never happened.
         */
        public final Map<UUID, Integer> witnesses = new HashMap<>();
        /** Reported from afar (api.Crimes.reportLater): it reaches the village and the faction only at the deadline. */
        public boolean deferred;

        public Crime(CrimeKind kind, GlobalPos village, UUID victim, long time) {
            this.kind = kind;
            this.village = village;
            this.victim = victim;
            this.time = time;
        }
    }

    /** Where a player stands with one village's law. */
    public static final class Standing {
        public final List<Crime> crimes = new ArrayList<>();
        /** Guards and golems attack on sight until this game time (0 = not hostile). */
        public long hostileUntil;
        /** When they last fled or died; a full day away cools things down. */
        public long leftAt;
        /** A friend let something slide; next one not before this game time. */
        public long forgivenUntil;
        /** Petty items taken while a golem watched (it snaps on the third). */
        public int watchedThefts;

        /** Crimes a guard will come and talk to you about. */
        public boolean wanted() {
            return crimes.stream().anyMatch(c -> c.reported && !c.toGolem);
        }

        public boolean hostile() {
            return hostileUntil > 0;
        }
    }

    private final Map<UUID, Map<GlobalPos, Standing>> players = new HashMap<>();

    public static CrimeLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(CrimeLedger::new, CrimeLedger::load, null),
                "villagechatter_crimes");
    }

    public Standing standing(UUID player, GlobalPos village) {
        setDirty();
        return players.computeIfAbsent(player, k -> new HashMap<>()).computeIfAbsent(village, k -> new Standing());
    }

    /** Every player's crimes this entity witnessed: {player, standing, crime}. */
    public List<Object[]> seenBy(UUID witness) {
        List<Object[]> out = new ArrayList<>();
        players.forEach((player, villages) -> villages.values().forEach(s -> {
            for (Crime c : s.crimes) if (c.witnesses.containsKey(witness)) out.add(new Object[] {player, s, c});
        }));
        return out;
    }

    /** Every player's standings: {player, village, standing}. */
    public List<Object[]> everything() {
        List<Object[]> out = new ArrayList<>();
        players.forEach((player, villages) -> villages.forEach((village, s) -> out.add(new Object[] {player, village, s})));
        return out;
    }

    public Map<GlobalPos, Standing> all(UUID player) {
        return players.getOrDefault(player, Map.of());
    }

    /** Old crimes are dropped, hits and all: people move on (after 3 days, or a week for a killing). */
    public void forget(long now) {
        players.values().forEach(m -> m.values().forEach(s -> s.crimes.removeIf(c -> now - c.time > (c.kind.murder ? 7 : 3) * 24000L)));
        players.values().forEach(m -> m.values().removeIf(s -> s.crimes.isEmpty() && s.hostileUntil < now && s.forgivenUntil < now));
        players.values().removeIf(Map::isEmpty);
        setDirty();
    }

    private static CrimeLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        CrimeLedger data = new CrimeLedger();
        for (Tag t : tag.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            Map<GlobalPos, Standing> villages = new HashMap<>();
            for (Tag vt : p.getList("villages", Tag.TAG_COMPOUND)) {
                CompoundTag v = (CompoundTag) vt;
                GlobalPos village = GlobalPos.CODEC.parse(NbtOps.INSTANCE, v.get("village")).result().orElse(null);
                if (village == null) continue;
                Standing s = new Standing();
                s.hostileUntil = v.getLong("hostileUntil");
                s.leftAt = v.getLong("leftAt");
                s.forgivenUntil = v.getLong("forgivenUntil");
                s.watchedThefts = v.getInt("watchedThefts");
                for (Tag ct : v.getList("crimes", Tag.TAG_COMPOUND)) {
                    CompoundTag c = (CompoundTag) ct;
                    Crime crime;
                    try {
                        crime = new Crime(CrimeKind.valueOf(c.getString("kind")), village,
                                c.hasUUID("victim") ? c.getUUID("victim") : null, c.getLong("time"));
                    } catch (IllegalArgumentException unknown) {
                        continue;
                    }
                    crime.seenByGuard = c.getBoolean("seenByGuard");
                    crime.reported = c.getBoolean("reported");
                    crime.toGolem = c.getBoolean("toGolem");
                    crime.fine = c.getInt("fine");
                    crime.hits = c.getInt("hits");
                    crime.villageHit = c.getDouble("villageHit");
                    crime.guardHit = c.getDouble("guardHit");
                    crime.deferred = c.getBoolean("deferred");
                    if (c.contains("chest")) crime.chest = NbtUtils.readBlockPos(c, "chest").orElse(null);
                    for (Tag wt : c.getList("witnesses", Tag.TAG_COMPOUND)) {
                        CompoundTag w = (CompoundTag) wt;
                        crime.witnesses.put(w.getUUID("id"), w.getInt("hit"));
                    }
                    for (Tag it : c.getList("stolen", Tag.TAG_COMPOUND)) {
                        ItemStack.parse(registries, it).ifPresent(crime.stolen::add);
                    }
                    s.crimes.add(crime);
                }
                villages.put(village, s);
            }
            data.players.put(p.getUUID("id"), villages);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        players.forEach((id, villages) -> {
            CompoundTag p = new CompoundTag();
            p.putUUID("id", id);
            ListTag vl = new ListTag();
            villages.forEach((village, s) -> {
                CompoundTag v = new CompoundTag();
                GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, village).result().ifPresent(e -> v.put("village", e));
                v.putLong("hostileUntil", s.hostileUntil);
                v.putLong("leftAt", s.leftAt);
                v.putLong("forgivenUntil", s.forgivenUntil);
                v.putInt("watchedThefts", s.watchedThefts);
                ListTag cl = new ListTag();
                for (Crime crime : s.crimes) {
                    CompoundTag c = new CompoundTag();
                    c.putString("kind", crime.kind.name());
                    if (crime.victim != null) c.putUUID("victim", crime.victim);
                    c.putLong("time", crime.time);
                    c.putBoolean("seenByGuard", crime.seenByGuard);
                    c.putBoolean("reported", crime.reported);
                    c.putBoolean("toGolem", crime.toGolem);
                    c.putInt("fine", crime.fine);
                    c.putInt("hits", crime.hits);
                    c.putDouble("villageHit", crime.villageHit);
                    c.putDouble("guardHit", crime.guardHit);
                    if (crime.deferred) c.putBoolean("deferred", true);
                    if (crime.chest != null) c.put("chest", NbtUtils.writeBlockPos(crime.chest));
                    ListTag wl = new ListTag();
                    crime.witnesses.forEach((w, hit) -> {
                        CompoundTag e = new CompoundTag();
                        e.putUUID("id", w);
                        e.putInt("hit", hit);
                        wl.add(e);
                    });
                    c.put("witnesses", wl);
                    ListTag items = new ListTag();
                    for (ItemStack stack : crime.stolen) if (!stack.isEmpty()) items.add(stack.save(registries));
                    c.put("stolen", items);
                    cl.add(c);
                }
                v.put("crimes", cl);
                vl.add(v);
            });
            p.put("villages", vl);
            list.add(p);
        });
        tag.put("players", list);
        return tag;
    }
}

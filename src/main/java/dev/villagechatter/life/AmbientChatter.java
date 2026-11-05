package dev.villagechatter.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Villagers chatting to each other: two who pass close stop for a moment, face each other and trade a few words of
 * villagerese ("oh hey / hi, how are you / fine / bye"), no subtitles. At the afternoon meet-up by the bell it's
 * far more common, longer, and can pull in a third villager. Only near players (nobody hears it otherwise).
 */
public final class AmbientChatter {
    private AmbientChatter() {}

    private static final int SCAN_EVERY = 40;
    private static final double PLAYER_RANGE = 32, PAIR_RANGE = 2.6;
    private static final double CHANCE = 0.06, MEET_CHANCE = 0.3;
    private static final int COOLDOWN = 20 * 30, MEET_COOLDOWN = 20 * 8;

    private record Chat(Set<UUID> members, int until) {}

    private static final Map<UUID, Chat> CHATS = new HashMap<>();
    private static final Map<UUID, Integer> NEXT = new HashMap<>();
    /** Who's in each chat, for keeping them still and facing each other. */
    private static final Map<UUID, Villager> ENTITIES = new HashMap<>();

    public static boolean isChatting(Villager v) {
        Chat c = CHATS.get(v.getUUID());
        return c != null && c.until > LivingVillage.tick();
    }

    /** The player butted in: the chat breaks up (they'll say so in their greeting). */
    public static void interrupt(Villager v) {
        Chat c = CHATS.remove(v.getUUID());
        if (c != null) c.members.forEach(id -> {
            CHATS.remove(id);
            ENTITIES.remove(id);
        });
    }

    static void tick(MinecraftServer server, int now) {
        holdStill(now);
        if (now % SCAN_EVERY != 0 || !ChatterConfig.AMBIENT_CHATTER.get()) return;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            for (Villager a : LivingVillage.villagersNear(level, player, PLAYER_RANGE, false)) {
                if (!seen.add(a.getUUID()) || !LivingVillage.idle(a) || NEXT.getOrDefault(a.getUUID(), 0) > now) continue;
                List<Villager> near = level.getEntitiesOfClass(Villager.class, a.getBoundingBox().inflate(PAIR_RANGE),
                        b -> b != a && LivingVillage.idle(b) && NEXT.getOrDefault(b.getUUID(), 0) <= now);
                if (near.isEmpty()) continue;
                boolean meeting = a.getBrain().isActive(Activity.MEET);
                if (level.random.nextDouble() >= (meeting ? MEET_CHANCE : CHANCE)) continue;
                List<Villager> group = new ArrayList<>();
                group.add(a);
                group.add(near.get(0));
                if (meeting && near.size() > 1 && level.random.nextBoolean()) group.add(near.get(1));
                start(level, group, meeting, now);
            }
        }
    }

    /** Takes turns talking: A greets, B answers, maybe back and forth a few times at the meet-up, then a goodbye. */
    static void start(ServerLevel level, List<Villager> group, boolean meeting, int now) {
        int turns = meeting ? 3 + level.random.nextInt(3) : 2 + level.random.nextInt(2);
        int t = 0;
        for (int i = 0; i < turns; i++) {
            Villager speaker = group.get(i % group.size());
            boolean question = level.random.nextFloat() < 0.4F;
            int syllables = (i == 0 || i == turns - 1) ? 2 + level.random.nextInt(2) : 3 + level.random.nextInt(4);
            t += LivingVillage.babble(speaker, syllables, t, question ? 0.12F : -0.06F, 0.55F);
            t += 8 + level.random.nextInt(10); // the pause while the other one thinks
        }
        Set<UUID> ids = new HashSet<>();
        for (Villager v : group) ids.add(v.getUUID());
        Chat chat = new Chat(ids, now + t + 10);
        int cooldown = meeting ? MEET_COOLDOWN : COOLDOWN;
        for (Villager v : group) {
            CHATS.put(v.getUUID(), chat);
            ENTITIES.put(v.getUUID(), v);
            NEXT.put(v.getUUID(), now + t + cooldown + level.random.nextInt(cooldown));
        }
    }

    /** Chatting villagers stand still and look at whoever they're talking to. */
    private static void holdStill(int now) {
        if (ENTITIES.isEmpty()) return;
        ENTITIES.entrySet().removeIf(e -> {
            Villager v = e.getValue();
            Chat c = CHATS.get(e.getKey());
            if (c == null || c.until <= now || !v.isAlive()) {
                CHATS.remove(e.getKey());
                return true;
            }
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.getNavigation().stop();
            for (UUID id : c.members) {
                Villager other = ENTITIES.get(id);
                if (other != null && other != v) {
                    v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(other, true));
                    break;
                }
            }
            return false;
        });
        if (NEXT.size() > 2000) NEXT.values().removeIf(t -> t < now);
    }
}

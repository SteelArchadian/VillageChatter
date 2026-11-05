package dev.villagechatter.life;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterConfig.VoiceStyle;
import dev.villagechatter.ChatterSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Living villages: what villagers do and say outside the dialogue box.
 * Chats in passing ({@link AmbientChatter}), curious onlookers and bed reactions ({@link Onlookers}, {@link Misdeeds}),
 * small kindnesses ({@link Kindness}), chest takes ({@link Misdeeds}) and guard escorts ({@link GuardEscort}).
 * This class holds the shared bits: a tiny scheduler, babble that nearby players hear, and "is this a village?".
 */
public final class LivingVillage {
    private LivingVillage() {}

    private record Task(int at, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static int now;

    /** Run something {@code ticks} from now (server thread). */
    public static void later(int ticks, Runnable run) {
        TASKS.add(new Task(now + Math.max(1, ticks), run));
    }

    public static int tick() {
        return now;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        now = event.getServer().getTickCount();
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            Iterator<Task> it = TASKS.iterator();
            while (it.hasNext()) {
                Task t = it.next();
                if (t.at() <= now) {
                    due.add(t);
                    it.remove();
                }
            }
            due.forEach(t -> t.run().run());
        }
        AmbientChatter.tick(event.getServer(), now);
        dev.villagechatter.life.law.Enforcement.tick(event.getServer(), now);
        dev.villagechatter.life.law.WitnessMemory.tick(event.getServer(), now);
        dev.villagechatter.life.law.GoodDeeds.tick(event.getServer(), now);
        if (now % 20 == 0 && ChatterConfig.LAW_AND_ORDER.get()) dev.villagechatter.life.law.Companion.tick(event.getServer());
        dev.villagechatter.life.law.Grief.tick(event.getServer(), now);
        Onlookers.tick(now);
        if (now % 20 == 0) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                Kindness.tickPlayer(player, now);
                GuardEscort.tickPlayer(player, now);
            }
        }
    }

    // ------------------------------------------------------------------ babble (heard by everyone nearby)

    /** The villager's own stable voice pitch (same formula as the dialogue voice). */
    static float pitch(UUID uuid) {
        long bits = uuid.getLeastSignificantBits() ^ uuid.getMostSignificantBits();
        return 0.82F + Math.floorMod(bits, 1000L) / 1000F * 0.4F;
    }

    /**
     * A short spoken burst in villagerese: {@code syllables} syllables starting {@code delay} ticks from now,
     * pitch sliding by {@code bend} (positive = rising, like a question).
     * @return how many ticks it takes
     */
    public static int babble(Entity speaker, int syllables, int delay, float bend, float volume) {
        if (!(speaker.level() instanceof ServerLevel level)) return 0;
        if (ChatterConfig.AMBIENT_VOICE.get() == ChatterConfig.AmbientVoice.VANILLA) {
            return AmbientVoices.vanillaLine(level, speaker, syllables, delay, bend, volume);
        }
        AmbientVoices.spoke(speaker, delay + syllables * 3);
        float base = pitch(speaker.getUUID()) * (speaker instanceof Villager v && v.isBaby() ? 1.32F : 1.0F);
        int t = delay;
        for (int i = 0; i < syllables; i++) {
            float k = syllables <= 1 ? 0 : i / (float) (syllables - 1);
            float p = base * (1 + bend * k) * (0.95F + level.random.nextFloat() * 0.1F);
            int at = t;
            later(at, () -> {
                if (!speaker.isAlive()) return;
                level.playSound(null, speaker.getX(), speaker.getEyeY(), speaker.getZ(),
                        ChatterSounds.VOICES.get(VoiceStyle.MURMUR).get(), SoundSource.NEUTRAL, volume, p);
            });
            t += 2 + level.random.nextInt(2);
        }
        return t - delay;
    }

    /** "Name: line", shown above the hotbar of one player (no chat spam). */
    public static void say(ServerPlayer player, Entity speaker, Component line) {
        player.displayClientMessage(Component.empty()
                .append(speaker.getName().copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(line), true);
    }

    // ------------------------------------------------------------------ villages

    public static List<Villager> villagersNear(ServerLevel level, Entity center, double radius, boolean adultsOnly) {
        return level.getEntitiesOfClass(Villager.class, new AABB(center.blockPosition()).inflate(radius),
                v -> v.isAlive() && (!adultsOnly || !v.isBaby()));
    }

    /** A few villagers around: good enough for "you're in a village". */
    static boolean inVillage(ServerLevel level, Entity center) {
        return villagersNear(level, center, 40, true).size() >= 2;
    }

    /** Free to wander over or stop for a chat: awake, not trading, not panicking or already busy with us. */
    public static boolean idle(Villager v) {
        return v.isAlive() && !v.isSleeping() && !v.isTrading() && v.getTarget() == null
                && !AmbientChatter.isChatting(v) && !Onlookers.isWatching(v) && !AmbientVoices.justSpoke(v);
    }
}

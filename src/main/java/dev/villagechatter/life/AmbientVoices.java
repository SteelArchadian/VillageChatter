package dev.villagechatter.life;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterConfig.AmbientVoice;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/**
 * Keeps villagers from talking over themselves. The vanilla idle "hmm" and this mod's villagerese can both play
 * (ambientVoice = BOTH), but never back to back from the same villager: after either one, that villager stays
 * quiet for a few seconds. VILLAGERESE mutes the vanilla idle sound; VANILLA swaps villagerese for vanilla sounds.
 * Hurt, death, trading and other vanilla sounds are never touched.
 */
public final class AmbientVoices {
    private AmbientVoices() {}

    /** The built-in pause between one villager's lines (ticks). Not configurable on purpose. */
    private static final int PAUSE = 20 * 5;

    /** Villager uuid -> tick until which they're done talking (plus the pause). */
    private static final Map<UUID, Integer> QUIET_UNTIL = new HashMap<>();

    static void spoke(Entity speaker, int ticks) {
        int until = LivingVillage.tick() + ticks + PAUSE;
        QUIET_UNTIL.merge(speaker.getUUID(), until, Math::max);
        if (QUIET_UNTIL.size() > 2000) QUIET_UNTIL.values().removeIf(t -> t < LivingVillage.tick());
    }

    /** Talked (either voice) within the last few seconds: don't start a new chat or reaction just yet. */
    static boolean justSpoke(Entity v) {
        return ChatterConfig.AMBIENT_VOICE.get() == AmbientVoice.BOTH
                && QUIET_UNTIL.getOrDefault(v.getUUID(), 0) > LivingVillage.tick();
    }

    /** The vanilla idle sound, as it's about to play: mute it or let it through (and start the pause). */
    public static void onLevelSound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getSound() == null || !event.getSound().is(SoundEvents.VILLAGER_AMBIENT.getLocation())) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        AmbientVoice mode = ChatterConfig.AMBIENT_VOICE.get();
        if (mode == AmbientVoice.VANILLA) return;
        Villager villager = at(level, event.getPosition());
        if (villager == null) return;
        // Muted in VILLAGERESE; while someone's talking with them the dialogue voice speaks for them; and the pause.
        if (mode == AmbientVoice.VILLAGERESE || ConversationManager.isInConversation(villager)
                || QUIET_UNTIL.getOrDefault(villager.getUUID(), 0) > LivingVillage.tick()) {
            event.setCanceled(true);
            return;
        }
        spoke(villager, 20);
    }

    /** Vanilla plays a mob's idle sound at its exact position. */
    private static Villager at(ServerLevel level, Vec3 pos) {
        List<Villager> found = level.getEntitiesOfClass(Villager.class, new AABB(pos, pos).inflate(0.05),
                v -> v.position().distanceToSqr(pos) < 0.0025);
        return found.isEmpty() ? null : found.get(0);
    }

    /** VANILLA mode: a chat line spoken with the vanilla villager sounds (one "hmm" per few syllables). */
    static int vanillaLine(ServerLevel level, Entity speaker, int syllables, int delay, float bend, float volume) {
        float base = LivingVillage.pitch(speaker.getUUID()) * (speaker instanceof Villager v && v.isBaby() ? 1.32F : 1.0F);
        int sounds = Math.max(1, (syllables + 2) / 4);
        int t = delay;
        for (int i = 0; i < sounds; i++) {
            float k = sounds <= 1 ? 1 : i / (float) (sounds - 1);
            float p = base * (1 + bend * k) * (0.95F + level.random.nextFloat() * 0.1F);
            LivingVillage.later(t, () -> {
                if (!speaker.isAlive()) return;
                level.playSound(null, speaker.getX(), speaker.getEyeY(), speaker.getZ(),
                        SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, volume, p);
            });
            t += 14 + level.random.nextInt(6);
        }
        return t - delay;
    }
}

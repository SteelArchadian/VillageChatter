package dev.villagechatter.client;

import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.ChatterConfig.VoiceStyle;
import dev.villagechatter.ChatterSounds;
import dev.villagechatter.network.ChatterNetwork;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Speaks dialogue lines. Villagers and NPCs babble one syllable per revealed letter (rate-limited per voice
 * style), pitched per speaker so every villager has its own voice. Golems and pets answer with their own
 * vanilla sounds.
 */
final class DialogueVoice {
    private static final RandomSource RANDOM = RandomSource.create();

    /** Children speak noticeably higher. */
    private static final float BABY_PITCH = 1.32F;

    private final Entity speaker;
    private final String kind;
    private final String pool;
    private final float pitch;
    private final int flags;
    private long lastSyllableMs;

    DialogueVoice(Entity speaker, int flags, String kind, String pool) {
        this.speaker = speaker;
        this.kind = kind;
        this.pool = pool;
        this.flags = flags;
        float mood = (flags & ChatterNetwork.FLAG_GRUMPY) != 0 ? 0.9F
                : (flags & ChatterNetwork.FLAG_HERO) != 0 ? 1.06F : 1.0F;
        float age = (flags & ChatterNetwork.FLAG_BABY) != 0 ? BABY_PITCH : 1.0F;
        this.pitch = voicePitch(speaker.getUUID()) * mood * age;
    }

    private boolean speaks() {
        return kind.equals(ChatterNetwork.KIND_VILLAGER) || kind.equals(ChatterNetwork.KIND_NPC);
    }

    // ------------------------------------------------------------------ lines in the dialogue box

    /** A new line starts scrolling. */
    void onLineStart(String text, String topic) {
        if (kind.equals(ChatterNetwork.KIND_GOLEM)) golem(topic);
        else if (kind.equals(ChatterNetwork.KIND_PET)) pet(topic);
        else if (kind.equals(ChatterNetwork.KIND_PIGLIN)) piglin(topic);
    }

    /** Called for every character as it appears. questionAhead lifts the pitch toward the end of a question. */
    void onCharRevealed(String flat, int index, boolean lineStart, boolean questionAhead) {
        VoiceStyle style = ChatterConfig.VOICE_STYLE.get();
        if (style == VoiceStyle.NONE || !speaks() || !Character.isLetter(flat.charAt(index))) return;
        long now = Util.getMillis();
        if (now - lastSyllableMs < style.minGapMs) return;
        lastSyllableMs = now;
        float p = style.basePitch * pitch * jitter(0.05F) * (questionAhead ? 1.08F : 1.0F);
        play(ChatterSounds.VOICES.get(style).get(), p);
    }

    // ------------------------------------------------------------------ after the window closes

    /**
     * Say a farewell with no text box to drive it: syllables are scheduled up front on the same rhythm the
     * text box would use, settling lower at the end.
     */
    void speakAfterClose(String text) {
        if (kind.equals(ChatterNetwork.KIND_GOLEM)) {
            golem("farewell");
            return;
        }
        if (kind.equals(ChatterNetwork.KIND_PET)) {
            pet("farewell");
            return;
        }
        if (kind.equals(ChatterNetwork.KIND_PIGLIN)) {
            piglin("farewell");
            return;
        }
        VoiceStyle style = ChatterConfig.VOICE_STYLE.get();
        if (style == VoiceStyle.NONE) return;
        int speed = Math.max(1, ChatterConfig.TEXT_SPEED.get());
        long t = 0, last = Long.MIN_VALUE / 2;
        int letters = 0;
        for (char c : text.toCharArray()) if (Character.isLetter(c)) letters++;
        int seen = 0;
        for (int i = 0; i < text.length() && t < 2500; i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                seen++;
                if (t - last >= style.minGapMs) {
                    last = t;
                    float settle = 1.0F - 0.1F * seen / Math.max(1, letters);
                    float p = style.basePitch * pitch * settle * jitter(0.05F);
                    Minecraft.getInstance().getSoundManager().playDelayed(voiceInstance(ChatterSounds.VOICES.get(style).get(), p), (int) (t / 50));
                }
            }
            t += 1000L / speed + (c == ',' ? 120 : c == '.' || c == '!' || c == '?' ? 260 : 0);
        }
    }

    // ------------------------------------------------------------------ creatures

    /**
     * Golemy: a grumbly old man of few words, made from the golem's own metal (tools/sound_gen/golem_phrases.py).
     * Each moment has its own set: "how are you?" gets a gruff "...fine", "you don't talk much?" a clipped "hm.".
     */
    private void golem(String topic) {
        String moment = switch (topic) {
            case "reply.howareyou" -> "fine";
            case "reply.quiet" -> "quiet";
            case "reply.give_poppy" -> "amused";
            case "reply.give_iron" -> "thanks";
            case "reply.nothing" -> "grumpy";
            case "farewell" -> "farewell";
            default -> {
                if ((flags & (ChatterNetwork.FLAG_COMBAT | ChatterNetwork.FLAG_DANGER)) != 0) yield "alert";
                if ((flags & (ChatterNetwork.FLAG_POPPY | ChatterNetwork.FLAG_IRON)) != 0) yield "curious";
                if ((flags & ChatterNetwork.FLAG_NIGHT) != 0 && RANDOM.nextFloat() < 0.4F) yield "tired";
                yield "greeting";
            }
        };
        playAt(ChatterSounds.golem(moment), jitter(0.03F), 1.0F);
    }

    /**
     * Piglins grunt in their own voice (tools/sound_gen/piglin_phrases.py): a wondering grunt over gold, a happy
     * one handing over the goods, a grumble when they don't like you.
     */
    private void piglin(String topic) {
        String moment = switch (topic) {
            case "piglin_look" -> "admire";
            case "piglin_give" -> "happy";
            case "piglin_ask" -> "curious";
            case "farewell" -> "farewell";
            default -> (flags & ChatterNetwork.FLAG_DISLIKED) != 0 ? "grumpy"
                    : (flags & ChatterNetwork.FLAG_GOLD) != 0 && topic.equals("greeting") ? "admire"
                    : topic.startsWith("smalltalk") && RANDOM.nextBoolean() ? "curious" : "greeting";
        };
        playAt(ChatterSounds.piglin(moment), pitch * jitter(0.04F), 1.0F);
    }

    /** Pets answer in their own (vanilla) voices, from where they are. */
    private void pet(String topic) {
        boolean sad = topic.equals("reply.gohome") || topic.equals("reply.nohome") || (flags & ChatterNetwork.FLAG_LEAVING) != 0;
        boolean excited = topic.equals("reply.trick") || topic.equals("reply.adventure") || topic.equals("reply.comeback")
                || (flags & ChatterNetwork.FLAG_HOME) != 0 && topic.equals("greeting");
        SoundEvent sound;
        if (pool.endsWith(".wolf")) {
            sound = sad ? SoundEvents.WOLF_WHINE : excited ? SoundEvents.WOLF_PANT : SoundEvents.WOLF_AMBIENT;
        } else if (pool.endsWith(".cat")) {
            sound = sad ? SoundEvents.CAT_BEG_FOR_FOOD : excited ? SoundEvents.CAT_PURREOW
                    : topic.equals("greeting") ? SoundEvents.CAT_PURR : SoundEvents.CAT_AMBIENT;
        } else {
            sound = SoundEvents.PARROT_AMBIENT;
        }
        float age = speaker instanceof LivingEntity living && living.isBaby() ? 1.5F : 1.0F;
        playAt(sound, age * jitter(0.08F) * (sad ? 0.95F : 1.0F), 1.0F);
    }

    private void playAt(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.NEUTRAL, volume,
                Mth.clamp(pitch, 0.5F, 2.0F), SoundInstance.createUnseededRandom(), speaker.getX(), speaker.getEyeY(), speaker.getZ()));
    }

    // ------------------------------------------------------------------ playback

    private static float jitter(float amount) {
        return 1.0F - amount + RANDOM.nextFloat() * amount * 2;
    }

    private static void play(SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(voiceInstance(sound, pitch));
    }

    private static SoundInstance voiceInstance(SoundEvent sound, float pitch) {
        return new SimpleSoundInstance(sound.getLocation(), SoundSource.VOICE, ChatterConfig.VOICE_VOLUME.get().floatValue(),
                Mth.clamp(pitch, 0.5F, 2.0F), SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.NONE, 0, 0, 0, true);
    }

    static void playUi(SoundEvent sound, float pitch, float volume) {
        if (!ChatterConfig.UI_SOUNDS.get()) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** Every NPC gets its own stable voice pitch, derived from its UUID. */
    static float voicePitch(UUID uuid) {
        long bits = uuid.getLeastSignificantBits() ^ uuid.getMostSignificantBits();
        return 0.82F + Math.floorMod(bits, 1000L) / 1000F * 0.4F;
    }
}

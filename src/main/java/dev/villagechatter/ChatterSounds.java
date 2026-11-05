package dev.villagechatter;

import java.util.EnumMap;
import java.util.Map;

import dev.villagechatter.ChatterConfig.VoiceStyle;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Sound events. The files and sounds.json are produced by tools/sound_gen/generate.py. */
public final class ChatterSounds {
    private ChatterSounds() {}

    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, VillageChatter.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> UI_OPEN = register("ui.open");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_HOVER = register("ui.hover");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_SELECT = register("ui.select");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_CLOSE = register("ui.close");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_ADVANCE = register("ui.advance");

    /** Syllable sets for the per-letter voice styles. */
    public static final Map<VoiceStyle, DeferredHolder<SoundEvent, SoundEvent>> VOICES = new EnumMap<>(VoiceStyle.class);

    static {
        for (VoiceStyle style : VoiceStyle.values()) {
            if (style != VoiceStyle.NONE) VOICES.put(style, register("voice." + style.id));
        }
    }

    /** Golemy's voice: one event per conversational moment (see DialogueVoice#golem). */
    public static final java.util.List<String> GOLEM_MOMENTS = java.util.List.of(
            "greeting", "fine", "quiet", "curious", "amused", "thanks", "grumpy", "farewell", "alert", "tired");
    private static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> GOLEM = new java.util.HashMap<>();

    static {
        for (String moment : GOLEM_MOMENTS) GOLEM.put(moment, register("voice.golem." + moment));
    }

    public static SoundEvent golem(String moment) {
        return GOLEM.getOrDefault(moment, GOLEM.get("greeting")).get();
    }

    /** Piglin voice: one event per moment (tools/sound_gen/piglin_phrases.py). */
    public static final java.util.List<String> PIGLIN_MOMENTS = java.util.List.of(
            "greeting", "curious", "admire", "happy", "grumpy", "angry", "farewell");
    private static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> PIGLIN = new java.util.HashMap<>();

    static {
        for (String moment : PIGLIN_MOMENTS) PIGLIN.put(moment, register("voice.piglin." + moment));
    }

    public static SoundEvent piglin(String moment) {
        return PIGLIN.getOrDefault(moment, PIGLIN.get("greeting")).get();
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(VillageChatter.id(name)));
    }

    static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}

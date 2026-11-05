package dev.villagechatter.client;

import java.util.ArrayList;
import java.util.List;

import dev.villagechatter.VillageChatter;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.CloseDialogue;
import dev.villagechatter.network.ChatterNetwork.DialogueLine;
import dev.villagechatter.network.ChatterNetwork.OpenDialogue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/** Client-side entry points for dialogue packets, plus line picking from lang files. */
public final class ClientDialogue {
    private ClientDialogue() {}

    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX_LINES_PER_POOL = 64;

    public static void open(OpenDialogue payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity speaker = mc.level.getEntity(payload.entityId());
        if (speaker == null) return;
        Component greeting = payload.extras().text()
                .orElseGet(() -> pickLine("greeting", payload.speakerPool(), payload.flags(), payload.kind(), speaker));
        mc.setScreen(new DialogueScreen(speaker, payload, greeting));
    }

    public static void line(DialogueLine payload) {
        if (Minecraft.getInstance().screen instanceof DialogueScreen screen && screen.entityId() == payload.entityId()) {
            Component line = payload.extras().text().orElseGet(() -> payload.topic().equals("repeat")
                    ? repeatLine(screen)
                    : pickLine(payload.topic(), screen.speakerPool(), screen.flags(), screen.kind(), screen.speaker()));
            if (payload.topic().equals("smalltalk")) SAID.put(payload.entityId(), line);
            screen.say(line, payload.topic(), payload.options(), payload.extras());
        }
    }

    public static void close(CloseDialogue payload) {
        if (Minecraft.getInstance().screen instanceof DialogueScreen screen && screen.entityId() == payload.entityId()) {
            screen.closeQuietly();
        }
    }

    /**
     * Picks a random line for a topic. Lines live in lang files as
     * villagechatter.&lt;topic&gt;.&lt;pool&gt;.&lt;n&gt; (n = 0, 1, 2... until one is missing), so resource packs
     * can add lines for modded professions without code, e.g.
     * villagechatter.greeting.profession.morevillagers.florist.0
     * Lines may use %s for the speaker's name (handy for pets).
     */
    static Component pickLine(String topic, String speakerPool, int flags, String kind, Entity speaker) {
        String key = pickKey(topic, speakerPool, flags, kind);
        return key == null ? Component.translatable(VillageChatter.MODID + ".fallback")
                : Component.translatable(key, speaker.getDisplayName());
    }

    private static String pickKey(String topic, String speakerPool, int flags, String kind) {
        List<String> keys = new ArrayList<>();
        boolean villager = (flags & ChatterNetwork.FLAG_VILLAGER) != 0;
        boolean creature = kind.equals(ChatterNetwork.KIND_PET) || kind.equals(ChatterNetwork.KIND_GOLEM);
        // Shared lines for a whole kind of speaker: all guards, all pets.
        String group = villager ? "" : kind.equals(ChatterNetwork.KIND_PET) ? "pet" : guardPool(speakerPool);

        // Situations replace normal chit-chat when there are lines for them, most urgent first.
        // Each looks for speaker-specific lines (e.g. guard.combat) before the shared ones (combat).
        for (String situation : situations(flags)) {
            collect(keys, topic, speakerPool + "." + situation, 2);
            if (keys.isEmpty()) collect(keys, topic, group + "." + situation, 2);
            if (keys.isEmpty() && !creature) collect(keys, topic, situation, 1);
            if (!keys.isEmpty()) return pick(keys);
        }

        if ((flags & ChatterNetwork.FLAG_GRUMPY) != 0) {
            collect(keys, topic, "grumpy", 1);
            if (!keys.isEmpty()) return pick(keys);
        }

        // Specific lines are weighted double so they show up often enough to notice.
        collect(keys, topic, speakerPool, 2);
        // Pets: species lines first, shared pet lines only when the species has none. Guards: both.
        if (!creature || keys.isEmpty()) collect(keys, topic, group, 2);
        // Non-villagers (traders, guards...) with their own lines don't use the village-flavoured generic set.
        if (villager || (keys.isEmpty() && !creature)) collect(keys, topic, "generic", 1);
        if ((flags & ChatterNetwork.FLAG_NIGHT) != 0) {
            if (villager) collect(keys, topic, "night", 1);
            else if (!group.isEmpty()) collect(keys, topic, group + ".night", 3);
        }
        if (villager && (flags & ChatterNetwork.FLAG_RAIN) != 0) collect(keys, topic, "rain", 1);
        if (villager && (flags & ChatterNetwork.FLAG_HERO) != 0) collect(keys, topic, "hero", 3);
        return keys.isEmpty() ? null : pick(keys);
    }

    private static List<String> situations(int flags) {
        List<String> out = new ArrayList<>(3);
        if ((flags & ChatterNetwork.FLAG_AFTER_TRADE) != 0) out.add("after_trade");
        if ((flags & ChatterNetwork.FLAG_BROWSED) != 0) out.add("browsed");
        if ((flags & ChatterNetwork.FLAG_FRIEND_GIFT) != 0) out.add("friend_gift");
        if ((flags & ChatterNetwork.FLAG_INTERRUPTED) != 0) out.add("interrupted");
        if ((flags & ChatterNetwork.FLAG_WATCHED) != 0) out.add("watching");
        if ((flags & ChatterNetwork.FLAG_BETRAYER) != 0) out.add("betrayer");
        if ((flags & ChatterNetwork.FLAG_COMBAT) != 0) out.add("combat");
        if ((flags & ChatterNetwork.FLAG_VICTORY) != 0) out.add("victory");
        if ((flags & ChatterNetwork.FLAG_DANGER) != 0) out.add("danger");
        if ((flags & ChatterNetwork.FLAG_HURT) != 0) out.add("hurt");
        if ((flags & ChatterNetwork.FLAG_LEAVING) != 0) out.add("leaving");
        if ((flags & ChatterNetwork.FLAG_HOME) != 0) out.add("home");
        if ((flags & ChatterNetwork.FLAG_POPPY) != 0) out.add("poppy");
        if ((flags & ChatterNetwork.FLAG_IRON) != 0) out.add("iron");
        if ((flags & ChatterNetwork.FLAG_HUNGRY) != 0) out.add("hungry");
        // Piglins: a grudge first, then the gold they can smell, then friendliness.
        if ((flags & ChatterNetwork.FLAG_DISLIKED) != 0) out.add("disliked");
        if ((flags & ChatterNetwork.FLAG_GOLD) != 0) out.add("gold");
        if ((flags & ChatterNetwork.FLAG_LIKED) != 0) out.add("liked");
        return out;
    }

    /** The last small-talk line each speaker (entity id) told you, so they can repeat themselves. */
    private static final java.util.Map<Integer, Component> SAID = new java.util.LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Component> eldest) {
            return size() > 32;
        }
    };

    /** "Like I said: ..." with something they told you already, or another tired line if they haven't. */
    private static Component repeatLine(DialogueScreen screen) {
        Component said = SAID.get(screen.entityId());
        if (said == null || RANDOM.nextInt(3) == 0) {
            return pickLine("tired", screen.speakerPool(), screen.flags(), screen.kind(), screen.speaker());
        }
        String prefix = pickKey("repeat", "generic", 0, screen.kind());
        return prefix == null ? said : Component.translatable(prefix, said);
    }

    /** Lines shown recently (any speaker), oldest first: skipped while there's anything else to say. */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();
    private static final int RECENT_MEMORY = 24;

    private static String pick(List<String> keys) {
        List<String> fresh = new ArrayList<>(keys.size());
        for (String key : keys) if (!RECENT.contains(key)) fresh.add(key);
        // Everything said lately? Take the one said longest ago instead of a random repeat.
        String chosen = fresh.isEmpty() ? oldestOf(keys) : fresh.get(RANDOM.nextInt(fresh.size()));
        RECENT.remove(chosen);
        RECENT.addLast(chosen);
        while (RECENT.size() > RECENT_MEMORY) RECENT.removeFirst();
        return chosen;
    }

    private static String oldestOf(List<String> keys) {
        for (String recent : RECENT) if (keys.contains(recent)) return recent;
        return keys.get(RANDOM.nextInt(keys.size()));
    }

    /**
     * The speaker says goodbye back once the window has closed (Goodbye, Carry on, Esc...): the line shows
     * above the hotbar and they say it.
     */
    static void farewell(Entity speaker, String speakerPool, int flags, String kind) {
        aside(speaker, speakerPool, flags, kind, "farewell");
    }

    /** A line said after the box has closed (a farewell, or a pet's reaction to "let's go"), above the hotbar. */
    static void aside(Entity speaker, String speakerPool, int flags, String kind, String topic) {
        Minecraft mc = Minecraft.getInstance();
        if (!speaker.isAlive()) return;
        Component line = pickLine(topic, speakerPool, flags, kind, speaker);
        boolean narrated = kind.equals(ChatterNetwork.KIND_GOLEM) || kind.equals(ChatterNetwork.KIND_PET);
        mc.gui.setOverlayMessage(narrated ? line.copy().withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY)
                : Component.empty()
                        .append(speaker.getDisplayName().copy().withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                        .append(line), false);
        DialogueVoice voice = new DialogueVoice(speaker, flags, kind, speakerPool);
        if (topic.equals("farewell")) voice.speakAfterClose(line.getString());
        else voice.onLineStart(line.getString(), topic);
    }

    /** Any modded entity with "guard" in its id shares the guard lines unless it has its own. */
    private static String guardPool(String speakerPool) {
        return speakerPool.startsWith("entity.") && speakerPool.contains("guard") ? "guard" : "";
    }

    private static void collect(List<String> out, String topic, String pool, int weight) {
        if (pool.isEmpty() || pool.startsWith(".")) return;
        String prefix = VillageChatter.MODID + "." + topic + "." + pool + ".";
        for (int i = 0; i < MAX_LINES_PER_POOL; i++) {
            String key = prefix + i;
            if (!I18n.exists(key)) break;
            for (int w = 0; w < weight; w++) out.add(key);
        }
    }
}

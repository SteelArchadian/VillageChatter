package dev.villagechatter.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * The player doesn't say the same thing every time either. An option can have alternative wordings,
 * villagechatter.option.&lt;id&gt;.&lt;n&gt; (anyone) and villagechatter.option.&lt;id&gt;.&lt;kind&gt;.&lt;n&gt; (e.g. pets only),
 * besides villagechatter.option.&lt;id&gt; itself. Each conversation picks one, never the same one twice in a row.
 */
final class OptionWording {
    private OptionWording() {}

    private static final Random RANDOM = new Random();
    /** Option id -> the key used last time, so it changes next time. */
    private static final Map<String, String> LAST = new HashMap<>();

    static Component pick(String id, String kind) {
        String base = "villagechatter.option." + id;
        List<String> keys = new ArrayList<>();
        keys.add(base);
        collect(keys, base + ".");
        collect(keys, base + "." + kind + ".");
        if (keys.size() > 1) keys.remove(LAST.get(id));
        String key = keys.get(RANDOM.nextInt(keys.size()));
        LAST.put(id, key);
        return Component.translatable(key);
    }

    private static void collect(List<String> keys, String prefix) {
        for (int i = 0; I18n.exists(prefix + i); i++) keys.add(prefix + i);
    }
}

package dev.villagechatter.reputation.faction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Villages &amp; Pillages rule: every line has variants. Each numbered line group (key.0, key.1, ...) under these
 * prefixes needs at least 3, numbered without gaps (the code picks key.random(n)).
 */
@GameTestHolder(QuestsModule.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class LineVariantGameTests {
    private LineVariantGameTests() {}

    private static final List<String> PREFIXES = List.of("villagechatter.job.reply.", "villagechatter.trader.",
            "villagechatter.heard.", "villagechatter.faction.warn.", "villagechatter.witness.", "villagechatter.allegiance.");

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void lineVariants(GameTestHelper helper) throws Exception {
        JsonObject lang;
        try (var in = LineVariantGameTests.class.getResourceAsStream("/assets/villagechatter/lang/en_us.json")) {
            helper.assertTrue(in != null, "en_us.json on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, Integer> groups = new TreeMap<>();
        for (String key : lang.keySet()) {
            if (PREFIXES.stream().noneMatch(key::startsWith)) continue;
            int dot = key.lastIndexOf('.');
            String tail = key.substring(dot + 1);
            if (!tail.chars().allMatch(Character::isDigit)) continue;
            groups.merge(key.substring(0, dot), 1, Integer::sum);
        }
        helper.assertTrue(!groups.isEmpty(), "found the line groups");
        groups.forEach((group, count) -> {
            helper.assertTrue(count >= 3, group + " has only " + count + " variants");
            for (int i = 0; i < count; i++) helper.assertTrue(lang.has(group + "." + i), group + "." + i + " is missing");
        });
        helper.succeed();
    }
}

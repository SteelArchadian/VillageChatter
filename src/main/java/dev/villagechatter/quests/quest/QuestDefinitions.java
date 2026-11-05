package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import dev.villagechatter.quests.QuestsModule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

/** Loads quest templates from {@code data/<namespace>/villagequests/quest/}. Reloads with /reload. */
public final class QuestDefinitions extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile Map<ResourceLocation, QuestDefinition> definitions = Map.of();

    public QuestDefinitions() {
        super(GSON, "villagequests/quest");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, QuestDefinition> loaded = new java.util.LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                loaded.put(id, QuestDefinition.parse(id, GsonHelper.convertToJsonObject(json, "quest")));
            } catch (RuntimeException e) {
                QuestsModule.LOGGER.error("Skipping quest {}: {}", id, e.getMessage());
            }
        });
        definitions = Map.copyOf(loaded);
        QuestsModule.LOGGER.info("Loaded {} village quests", loaded.size());
    }

    @Nullable
    public static QuestDefinition get(ResourceLocation id) {
        return definitions.get(id);
    }

    public static Map<ResourceLocation, QuestDefinition> all() {
        return definitions;
    }

    /** Weighted pick among guard requests ("givers": "guard"). */
    @Nullable
    public static QuestDefinition pickForGuard(java.util.Set<ResourceLocation> exclude, RandomSource random) {
        return pickFor(QuestDefinition.Givers.GUARD, exclude, random);
    }

    /** A weighted pick among the quests of one non-villager giver kind (guards, golems). */
    public static QuestDefinition pickFor(QuestDefinition.Givers givers, java.util.Set<ResourceLocation> exclude, RandomSource random) {
        List<QuestDefinition> fitting = new ArrayList<>();
        int total = 0;
        for (QuestDefinition def : definitions.values()) {
            if (def.weight() <= 0 || exclude.contains(def.id()) || def.givers() != givers) continue;
            fitting.add(def);
            total += def.weight();
        }
        if (total == 0) return null;
        int roll = random.nextInt(total);
        for (QuestDefinition def : fitting) {
            roll -= def.weight();
            if (roll < 0) return def;
        }
        return null;
    }

    /**
     * Weighted pick among quests that fit this villager (quests with no profession list fit every profession;
     * babies only get quests meant for them). Quests in {@code exclude} are skipped for variety.
     */
    @Nullable
    public static QuestDefinition pickFor(ResourceLocation profession, boolean baby, java.util.Set<ResourceLocation> exclude,
                                          RandomSource random) {
        List<QuestDefinition> fitting = new ArrayList<>();
        int total = 0;
        for (QuestDefinition def : definitions.values()) {
            if (def.weight() <= 0 || exclude.contains(def.id()) || !def.givers().allows(baby)) continue;
            if (!baby && !def.professions().isEmpty() && !def.professions().contains(profession)) continue;
            fitting.add(def);
            total += def.weight();
        }
        if (total == 0) return null;
        int roll = random.nextInt(total);
        for (QuestDefinition def : fitting) {
            roll -= def.weight();
            if (roll < 0) return def;
        }
        return null;
    }
}

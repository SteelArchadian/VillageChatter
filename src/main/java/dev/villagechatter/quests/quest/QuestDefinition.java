package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A quest template loaded from {@code data/<namespace>/villagequests/quest/<name>.json}.
 * Types: {@code villagequests:heirloom} (guardian holds an item at a target), {@code villagequests:errand}
 * (bring the villager some items) and {@code villagequests:job} (a jobless villager asks you to find them work).
 * Text comes from lang keys derived from the id ({@code villagequests.quest.<namespace>.<name>.title} etc.)
 * unless the JSON names its own keys. Item stacks are kept as JSON and parsed on use with the server's registries,
 * so enchantments and other datapack-registry components work.
 */
public record QuestDefinition(
        ResourceLocation id,
        String type,
        int weight,
        Givers givers,
        Set<ResourceLocation> professions,
        @Nullable JsonElement item,
        @Nullable Guardian guardian,
        @Nullable Location location,
        @Nullable Request request,
        double deadlineDays,
        Rewards rewards,
        boolean celebrateGolems,
        Text text,
        int variants,
        Set<ResourceLocation> recipients,
        @Nullable JsonObject build) {

    public static final String TYPE_HEIRLOOM = "villagequests:heirloom";
    public static final String TYPE_ERRAND = "villagequests:errand";
    public static final String TYPE_JOB = "villagequests:job";
    public static final String TYPE_RESCUE = "villagequests:rescue";
    /** Carry a package to a profession in a neighbouring village (which you discover on the way). */
    public static final String TYPE_COURIER = "villagequests:courier";
    public static final String TYPE_BUILD = "villagequests:build";
    /** A kid's lost cat or dog, waiting in a forest or cave nearby (see LostPet). */
    public static final String TYPE_LOST_PET = "villagequests:lost_pet";

    /** Who can hand out this quest. Babies never hand out heirlooms. */
    public enum Givers {
        ADULT, BABY, ANY,
        /** Village guards (see GuardQuests). Villagers never get these. */
        GUARD,
        /** Iron golems that trust you (see GuardQuests): bring home a lost villager. */
        GOLEM;

        public boolean allows(boolean baby) {
            return this != GUARD && this != GOLEM && (this == ANY || (this == BABY) == baby);
        }
    }

    public record Guardian(ResourceLocation entity, Map<EquipmentSlot, JsonElement> equipment,
                           List<Effect> effects, double healthMultiplier) {}

    public record Effect(ResourceLocation id, int amplifier) {}

    /** kinds: tried in order, any of "structure", "cave", "surface". */
    public record Location(List<String> kinds, @Nullable ResourceLocation structureTag, int minDistance, int maxDistance) {
        public int clampedMin() {
            return Math.max(minDistance, QuestConfig.SEARCH_DISTANCE_MIN.get());
        }

        public int clampedMax() {
            return Math.max(clampedMin() + 16, Math.min(maxDistance, QuestConfig.SEARCH_DISTANCE_MAX.get()));
        }

        /** Structure lookups don't generate chunks, so they use the JSON's own range (not clamped by the config). */
        public int structureMax() {
            return Math.max(clampedMin() + 16, maxDistance);
        }
    }

    /** What an errand asks for: one item, or any item from a tag (then {@code displayKey} names it). */
    public record Request(@Nullable ResourceLocation item, @Nullable ResourceLocation tag, @Nullable String displayKey,
                          int min, int max) {
        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (item != null) return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item);
            return tag != null && stack.is(TagKey.create(Registries.ITEM, tag));
        }

        public Component displayName() {
            if (displayKey != null) return Component.translatable(displayKey);
            if (item != null) {
                Item it = BuiltInRegistries.ITEM.get(item);
                return new ItemStack(it).getHoverName();
            }
            return Component.literal("#" + tag);
        }
    }

    /**
     * Heirlooms: fixed emerald range + loot table + reputation for returned / lied / expired.
     * Errands: emeralds and XP per requested item, plus goodwill (fractional reputation) for the giver and,
     * a smaller share, for every other villager in the village.
     */
    public record Rewards(int emeraldsMin, int emeraldsMax, @Nullable ResourceLocation lootTable,
                          int repReturned, int repLied, int repExpired,
                          double emeraldsPerItem, double xpPerItem, double goodwillGiver, double goodwillVillage,
                          int xp, double discountDays, double discount) {}

    /** Lang keys. */
    public record Text(String title, String story, String itemName, String guardianName, String thanks, String done) {}

    /**
     * Story variants: with "variants": 3 the lines are key, key.1, key.2. Each villager sticks to one set,
     * so the thank-you matches the story they told.
     */
    public String variant(String key, java.util.UUID villager) {
        if (variants <= 1) return key;
        int i = Math.floorMod(villager.hashCode() * 31 + id.hashCode(), variants);
        return i == 0 ? key : key + "." + i;
    }

    /** "Our village has no golem, could you build us one?" See {@link Build}. */
    public boolean isBuild() {
        return TYPE_BUILD.equals(type);
    }

    public boolean isCourier() {
        return TYPE_COURIER.equals(type);
    }

    public boolean isErrand() {
        return TYPE_ERRAND.equals(type);
    }

    public boolean isHeirloom() {
        return TYPE_HEIRLOOM.equals(type);
    }

    public boolean isRescue() {
        return TYPE_RESCUE.equals(type);
    }

    public boolean isLostPet() {
        return TYPE_LOST_PET.equals(type);
    }

    /** Type-specific settings: a build quest's "requirement", a lost pet's "pet" block. */
    public JsonObject settings() {
        return build == null ? new JsonObject() : build;
    }

    /** Quests with a place to go (target search, note with coordinates). */
    public boolean hasLocation() {
        return isHeirloom() || isRescue() || isLostPet();
    }

    public boolean isJob() {
        return TYPE_JOB.equals(type);
    }

    /** Small quests: no grudge when they lapse or are abandoned. */
    public boolean isMinor() {
        return !isHeirloom();
    }

    public long deadlineTicks() {
        double days = deadlineDays > 0 ? deadlineDays : QuestConfig.DEFAULT_DEADLINE_DAYS.get();
        return Math.round(days * 24000.0);
    }

    public Optional<ItemStack> createItem(HolderLookup.Provider registries) {
        return item == null ? Optional.empty() : parseStack(item, registries);
    }

    public static Optional<ItemStack> parseStack(JsonElement json, HolderLookup.Provider registries) {
        JsonElement full = json;
        if (json.isJsonPrimitive()) {
            JsonObject obj = new JsonObject();
            obj.add("id", json);
            full = obj;
        }
        return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), full)
                .resultOrPartial(err -> QuestsModule.LOGGER.error("Bad item in quest JSON: {}", err));
    }

    // ------------------------------------------------------------------ parsing

    public static QuestDefinition parse(ResourceLocation id, JsonObject json) {
        String type = GsonHelper.getAsString(json, "type");
        boolean heirloom = type.equals(TYPE_HEIRLOOM);
        boolean errand = type.equals(TYPE_ERRAND);
        boolean job = type.equals(TYPE_JOB);
        boolean rescue = type.equals(TYPE_RESCUE);
        boolean courier = type.equals(TYPE_COURIER);
        boolean build = type.equals(TYPE_BUILD);
        boolean lostPet = type.equals(TYPE_LOST_PET);
        if (!heirloom && !errand && !job && !rescue && !courier && !build && !lostPet) throw new JsonParseException("Unknown quest type " + type);
        JsonObject buildRequirement = build ? GsonHelper.getAsJsonObject(json, "requirement")
                : lostPet ? GsonHelper.getAsJsonObject(json, "pet")
                : rescue ? GsonHelper.getAsJsonObject(json, "buddy", new JsonObject()) : null;
        if (lostPet && GsonHelper.getAsJsonArray(buildRequirement, "pets", new JsonArray()).isEmpty()) throw new JsonParseException("lost_pet needs pet.pets");

        Givers givers = switch (GsonHelper.getAsString(json, "givers", "adult")) {
            case "adult" -> Givers.ADULT;
            case "baby" -> Givers.BABY;
            case "any" -> Givers.ANY;
            case "guard" -> Givers.GUARD;
            case "golem" -> Givers.GOLEM;
            default -> throw new JsonParseException("givers must be adult, baby, any, guard or golem");
        };
        if (givers == Givers.GUARD && !errand && !build) throw new JsonParseException("guards only hand out errands and build requests");
        if (lostPet && givers != Givers.BABY) throw new JsonParseException("lost pets are kids' quests (givers: baby)");
        if (givers == Givers.GOLEM && !rescue) throw new JsonParseException("golems only ask for rescues");
        if ((heirloom || job || rescue || courier || build) && givers != Givers.ADULT && givers != Givers.GUARD && givers != Givers.GOLEM) throw new JsonParseException(type + " quests come from adults only");

        Set<ResourceLocation> professions = new HashSet<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(json, "professions", new JsonArray())) {
            professions.add(ResourceLocation.parse(e.getAsString()));
        }
        // Only jobless villagers can ask for a job.
        if (job) professions = Set.of(ResourceLocation.withDefaultNamespace("none"));

        JsonElement item = null;
        Guardian guardian = null;
        Location location = null;
        Request request = null;
        if (heirloom) {
            if (!json.has("item")) throw new JsonParseException("Missing item");
            item = json.get("item");
            guardian = parseGuardian(GsonHelper.getAsJsonObject(json, "guardian"));
            location = parseLocation(GsonHelper.getAsJsonObject(json, "location", new JsonObject()));
        } else if (rescue || lostPet) {
            location = parseLocation(GsonHelper.getAsJsonObject(json, "location", new JsonObject()));
        } else if (errand) {
            request = parseRequest(GsonHelper.getAsJsonObject(json, "request"));
        } else if (courier) {
            // The package the giver hands you: a real item stack, so it must be one item (not a tag).
            request = parseRequest(GsonHelper.getAsJsonObject(json, "package"));
            if (request.item() == null) throw new JsonParseException("courier package needs an item, not a tag");
        }
        Set<ResourceLocation> recipients = new HashSet<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(json, "recipients", new JsonArray())) {
            recipients.add(ResourceLocation.parse(e.getAsString()));
        }

        JsonObject r = GsonHelper.getAsJsonObject(json, "rewards", new JsonObject());
        JsonObject em = GsonHelper.getAsJsonObject(r, "emeralds", new JsonObject());
        JsonObject rep = GsonHelper.getAsJsonObject(r, "reputation", new JsonObject());
        JsonObject gw = GsonHelper.getAsJsonObject(r, "goodwill", new JsonObject());
        String loot = GsonHelper.getAsString(r, "loot_table", "");
        Rewards rewards = new Rewards(GsonHelper.getAsInt(em, "min", heirloom ? 4 : 0), GsonHelper.getAsInt(em, "max", heirloom ? 8 : 0),
                loot.isEmpty() ? null : ResourceLocation.parse(loot),
                GsonHelper.getAsInt(rep, "returned", 10), GsonHelper.getAsInt(rep, "lied", -5), GsonHelper.getAsInt(rep, "expired", -15),
                GsonHelper.getAsDouble(r, "emeralds_per_item", 0.0), GsonHelper.getAsDouble(r, "xp_per_item", 0.0),
                GsonHelper.getAsDouble(gw, "giver", 1.0), GsonHelper.getAsDouble(gw, "village", 0.25),
                GsonHelper.getAsInt(r, "xp", 0), GsonHelper.getAsDouble(r, "discount_days", 0.0), GsonHelper.getAsDouble(r, "discount", 0.2));

        String base = "villagequests.quest." + id.getNamespace() + "." + id.getPath().replace('/', '.') + ".";
        JsonObject t = GsonHelper.getAsJsonObject(json, "text", new JsonObject());
        Text text = new Text(
                GsonHelper.getAsString(t, "title", base + "title"),
                GsonHelper.getAsString(t, "story", base + "story"),
                GsonHelper.getAsString(t, "item", base + "item"),
                GsonHelper.getAsString(t, "guardian", base + "guardian"),
                GsonHelper.getAsString(t, "thanks", base + "thanks"),
                GsonHelper.getAsString(t, "done", base + "done"));

        double defaultDeadline = errand ? 2.0 : job ? 5.0 : rescue ? 6.0 : lostPet ? 3.0 : courier ? 4.0 : build ? 7.0 : 0.0;
        return new QuestDefinition(id, type, GsonHelper.getAsInt(json, "weight", 10), givers, professions, item, guardian,
                location, request, GsonHelper.getAsDouble(json, "deadline_days", defaultDeadline), rewards,
                GsonHelper.getAsBoolean(json, "celebrate_golems", false), text, Math.max(1, GsonHelper.getAsInt(json, "variants", 1)),
                recipients, buildRequirement);
    }

    private static Guardian parseGuardian(JsonObject g) {
        Map<EquipmentSlot, JsonElement> equipment = new EnumMap<>(EquipmentSlot.class);
        JsonObject eq = GsonHelper.getAsJsonObject(g, "equipment", new JsonObject());
        for (Map.Entry<String, JsonElement> e : eq.entrySet()) {
            equipment.put(EquipmentSlot.byName(e.getKey()), e.getValue());
        }
        List<Effect> effects = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(g, "effects", new JsonArray())) {
            JsonObject o = e.getAsJsonObject();
            effects.add(new Effect(ResourceLocation.parse(GsonHelper.getAsString(o, "id")), GsonHelper.getAsInt(o, "amplifier", 0)));
        }
        return new Guardian(ResourceLocation.parse(GsonHelper.getAsString(g, "entity")), equipment, effects,
                GsonHelper.getAsDouble(g, "health_multiplier", 1.0));
    }

    private static Location parseLocation(JsonObject l) {
        List<String> kinds = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(l, "kinds", new JsonArray())) kinds.add(e.getAsString());
        if (kinds.isEmpty()) kinds = List.of("cave");
        String structures = GsonHelper.getAsString(l, "structures", "");
        ResourceLocation structureTag = structures.isEmpty() ? null
                : ResourceLocation.parse(structures.startsWith("#") ? structures.substring(1) : structures);
        return new Location(kinds, structureTag, GsonHelper.getAsInt(l, "min_distance", 48), GsonHelper.getAsInt(l, "max_distance", 256));
    }

    /** {"item": "minecraft:poppy", "count": {"min": 10, "max": 16}} or {"tag": "...", "display": "lang.key", ...} */
    private static Request parseRequest(JsonObject q) {
        String item = GsonHelper.getAsString(q, "item", "");
        String tag = GsonHelper.getAsString(q, "tag", "");
        if (item.isEmpty() == tag.isEmpty()) throw new JsonParseException("request needs exactly one of item or tag");
        if (tag.startsWith("#")) tag = tag.substring(1);
        String display = GsonHelper.getAsString(q, "display", "");
        int min, max;
        if (q.has("count") && q.get("count").isJsonPrimitive()) {
            min = max = GsonHelper.getAsInt(q, "count");
        } else {
            JsonObject c = GsonHelper.getAsJsonObject(q, "count", new JsonObject());
            min = GsonHelper.getAsInt(c, "min", 1);
            max = GsonHelper.getAsInt(c, "max", min);
        }
        if (min < 1 || max < min) throw new JsonParseException("bad request count");
        return new Request(item.isEmpty() ? null : ResourceLocation.parse(item), tag.isEmpty() ? null : ResourceLocation.parse(tag),
                display.isEmpty() ? null : display, min, max);
    }
}

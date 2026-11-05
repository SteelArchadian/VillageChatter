package dev.villagechatter.reputation;

import dev.villagechatter.ChatterConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Fable-style gifts. Each profession (and kids) has things they love, like, or will take politely; anything else is
 * handed back ("this isn't really my kind of thing"). The villager's opinion rises by how much they liked it, and a
 * small share (15%) spreads to the village. Two accepted gifts per villager per day, so it can't be farmed.
 * The rise goes into goodwill, which pays out as vanilla gossip, so it also nudges trade prices down a little.
 *
 * Preferences: {@code data/<ns>/villagequests/gifts/<name>.json} with {@code professions} (empty = every adult),
 * {@code baby: true} for kids, and {@code love}/{@code like}/{@code meh} lists of item ids or #tags.
 */
public final class Gifts {
    private Gifts() {}

    public enum Reaction {
        LOVE(4.0), LIKE(2.0), MEH(0.75), REJECT(0), TOO_MANY(0), DISLIKE(0);

        public final double goodwill;

        Reaction(double goodwill) {
            this.goodwill = goodwill;
        }
    }

    public static final int GIFTS_PER_DAY = 2;
    public static final double VILLAGE_SHARE = 0.15;
    /** How many reaction lines each kind has in the lang file (villagequests.gift.&lt;kind&gt;[.kid].N). */
    private static final Map<Reaction, Integer> LINES = Map.of(Reaction.LOVE, 5, Reaction.LIKE, 5, Reaction.MEH, 4,
            Reaction.REJECT, 5, Reaction.TOO_MANY, 3, Reaction.DISLIKE, 3);

    // ------------------------------------------------------------------ preferences (datapack)

    record Preferences(List<ResourceLocation> professions, boolean baby, List<String> love, List<String> like, List<String> meh,
                       boolean acceptAnything) {}

    private static volatile List<Preferences> preferences = List.of();

    public static final class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        public Loader() {
            super(GSON, "villagequests/gifts");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            List<Preferences> loaded = new ArrayList<>();
            files.forEach((id, json) -> {
                try {
                    JsonObject o = GsonHelper.convertToJsonObject(json, "gifts");
                    List<ResourceLocation> professions = new ArrayList<>();
                    for (JsonElement e : GsonHelper.getAsJsonArray(o, "professions", new JsonArray())) professions.add(ResourceLocation.parse(e.getAsString()));
                    loaded.add(new Preferences(professions, GsonHelper.getAsBoolean(o, "baby", false),
                            strings(o, "love"), strings(o, "like"), strings(o, "meh"), GsonHelper.getAsBoolean(o, "accept_anything", false)));
                } catch (RuntimeException e) {
                    QuestsModule.LOGGER.error("Skipping gift preferences {}: {}", id, e.getMessage());
                }
            });
            preferences = List.copyOf(loaded);
        }

        private static List<String> strings(JsonObject o, String key) {
            List<String> out = new ArrayList<>();
            for (JsonElement e : GsonHelper.getAsJsonArray(o, key, new JsonArray())) out.add(e.getAsString());
            return out;
        }
    }

    /** How this villager feels about this item (ignoring the daily cap). */
    public static Reaction react(Villager villager, ItemStack stack) {
        if (stack.isEmpty()) return Reaction.REJECT;
        ResourceLocation profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        boolean baby = villager.isBaby();
        List<Preferences> own = new ArrayList<>(), general = new ArrayList<>();
        for (Preferences p : preferences) {
            if (p.baby() != baby) continue;
            if (p.professions().isEmpty()) general.add(p);
            else if (!baby && p.professions().contains(profession)) own.add(p);
        }
        // Their own trade's favorites first, then what everybody likes.
        for (List<Preferences> group : List.of(own, general)) {
            for (Preferences p : group) if (matchesAny(stack, p.love())) return Reaction.LOVE;
            for (Preferences p : group) if (matchesAny(stack, p.like())) return Reaction.LIKE;
        }
        // Then food, judged by what it's made of (GiftClasses): rotten food is an insult (sweets never are), kids
        // love sweets, and everyone likes any other food.
        if (villager.getServer() != null) {
            var kinds = GiftClasses.of(villager.getServer(), stack);
            if (kinds.contains(GiftClasses.Kind.ROTTEN) && !kinds.contains(GiftClasses.Kind.SWEET)) return Reaction.DISLIKE;
            if (baby && kinds.contains(GiftClasses.Kind.SWEET)) return Reaction.LOVE;
            if (kinds.contains(GiftClasses.Kind.FOOD)) return Reaction.LIKE;
        }
        for (List<Preferences> group : List.of(own, general)) {
            for (Preferences p : group) if (matchesAny(stack, p.meh()) || p.acceptAnything()) return Reaction.MEH;
        }
        return Reaction.REJECT;
    }

    private static boolean matchesAny(ItemStack stack, List<String> entries) {
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag != null && stack.is(TagKey.create(Registries.ITEM, tag))) return true;
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ daily cap

    /** Per player: villager -> {day, gifts that day}. */
    public static final class Given {
        public static final Codec<Given> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG.listOf())
                .xmap(Given::new, g -> g.byVillager);
        final Map<UUID, List<Long>> byVillager;

        public Given() {
            this(new HashMap<>());
        }

        private Given(Map<UUID, List<Long>> map) {
            this.byVillager = new HashMap<>(map);
        }

        int today(UUID villager, long day) {
            List<Long> v = byVillager.get(villager);
            return v == null || v.size() < 2 || v.get(0) != day ? 0 : v.get(1).intValue();
        }

        void count(UUID villager, long day) {
            byVillager.put(villager, List.of(day, (long) today(villager, day) + 1));
            if (byVillager.size() > 512) byVillager.entrySet().removeIf(e -> e.getValue().get(0) < day);
        }
    }

    // ------------------------------------------------------------------ giving

    public record Result(Reaction reaction, Component line) {}

    /** Gives one of the item in that inventory slot. Rejected gifts stay with the player. */
    public static Result give(ServerPlayer player, Villager villager, int slot) {
        ItemStack stack = slot >= 0 && slot < player.getInventory().getContainerSize() ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
        if (stack.isEmpty()) return new Result(Reaction.REJECT, Component.translatable("villagequests.gift.nothing"));
        Component itemName = stack.getHoverName();
        long day = player.serverLevel().getGameTime() / 24000L;
        Given given = player.getData(QuestRegistries.GIFTS_GIVEN);
        if (dev.villagechatter.life.Charity.isBeggar(villager) && dev.villagechatter.life.Charity.isAlms(stack)) {
            return new Result(Reaction.LIKE, dev.villagechatter.life.Charity.give(player, villager, stack));
        }

        Reaction reaction = react(villager, stack);
        if (reaction != Reaction.REJECT && given.today(villager.getUUID(), day) >= GIFTS_PER_DAY) reaction = Reaction.TOO_MANY;

        if (reaction == Reaction.DISLIKE) {
            // the wrong gift: handed back, and a little offence taken
            Reputation.addGoodwill(player, villager, -ChatterConfig.WRONG_GIFT_LOSS.get(), 0);
            villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, villager.getVoicePitch());
            return new Result(reaction, line(villager, reaction, itemName));
        }
        if (reaction.goodwill > 0) {
            Item item = stack.getItem();
            // a cooked meal goes down a little better than raw food
            double cooked = GiftClasses.is(player.server, stack, GiftClasses.Kind.COOKED) ? ChatterConfig.COOKED_GIFT_BONUS.get() : 1;
            stack.shrink(1);
            player.getInventory().setChanged();
            given.count(villager.getUUID(), day);
            Reputation.addGoodwill(player, villager, reaction.goodwill * cooked, reaction.goodwill * cooked * VILLAGE_SHARE);
            if (villager.isBaby() && reaction.goodwill > 0) {
                dev.villagechatter.life.law.Law.goodDeed(player, villager, reaction.goodwill / 2, reaction.goodwill / 4); // the golems love the kids
            }
            if (villager.level() instanceof ServerLevel level && reaction == Reaction.LOVE) {
                level.sendParticles(ParticleTypes.HEART, villager.getX(), villager.getEyeY() + 0.5, villager.getZ(), 5, 0.4, 0.3, 0.4, 0);
            }
            villager.playSound(reaction == Reaction.MEH ? SoundEvents.VILLAGER_YES : SoundEvents.VILLAGER_CELEBRATE, 1.0F, villager.getVoicePitch());
            QuestsModule.LOGGER.debug("Gift {} to {}: {}", BuiltInRegistries.ITEM.getKey(item), villager.getName().getString(), reaction);
        } else {
            villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, villager.getVoicePitch());
        }
        return new Result(reaction, line(villager, reaction, itemName));
    }

    private static Component line(Villager villager, Reaction reaction, Component item) {
        String kind = reaction.name().toLowerCase(java.util.Locale.ROOT);
        String key = "villagequests.gift." + kind + (villager.isBaby() ? ".kid" : "") + "."
                + villager.getRandom().nextInt(villager.isBaby() ? 2 : LINES.get(reaction));
        return Component.translatable(key, item);
    }
}

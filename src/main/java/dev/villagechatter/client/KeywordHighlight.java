package dev.villagechatter.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * BotW-style highlights: names of items and places in a line ("an emerald", "the Plains", "a pillager outpost")
 * get their own colour. Items take their rarity's colour (modded rarities and items that colour their own name
 * included), common items and places a teal. Enchanted things ("enchanted book", "an enchanted iron sword") shimmer.
 * Items come from the item registry (modded ones too, in the current language), places from the biome registry plus
 * a short list of structures and dimensions. Longest names match first, whole words only.
 */
final class KeywordHighlight {
    private KeywordHighlight() {}

    /** Colour of places and of common items. */
    static final int PLACE_COLOR = 0xFF52E3D6;
    private static final int RARE_COLOR = 0xFF55FFFF;

    /** Item names that are everyday words in a sentence, not things you'd hand over. */
    private static final Set<String> SKIP = Set.of("air", "light", "fire", "water", "lava", "barrier", "map", "book",
            "stick", "stone", "string", "glass", "paper", "clay", "snow", "ice", "dirt", "grass", "sand", "gravel",
            "bow", "bowl", "bell", "door", "bed", "chest", "torch", "egg", "end", "fern", "vine", "web", "button",
            "lever", "sign", "spawner", "jigsaw", "structure void", "debug stick", "knowledge book", "light block");
    private static final List<String> PLACES = List.of("village", "villages", "pillager outpost", "outpost",
            "woodland mansion", "mansion", "ocean monument", "stronghold", "mineshaft", "ancient city", "trail ruins",
            "desert pyramid", "jungle temple", "igloo", "shipwreck", "ruined portal", "swamp hut", "witch hut",
            "bastion", "nether fortress", "fortress", "end city", "the nether", "nether", "the end", "overworld",
            "trial chambers");
    private static final String ENCHANTED = "enchanted ";

    /** How a name is shown: its colour, and whether it shimmers like enchanted gear. */
    private record Look(int color, boolean foil, boolean item) {}

    /** Per character of a line: colour (0 = plain text) and shimmer. */
    record Marks(int[] color, boolean[] shimmer) {
        static Marks none(int length) {
            return new Marks(new int[length], new boolean[length]);
        }
    }

    private static Map<String, Look> looks;
    private static List<String> names;
    private static String language;

    private static List<String> names() {
        Minecraft mc = Minecraft.getInstance();
        String lang = mc.getLanguageManager().getSelected();
        if (names != null && lang.equals(language)) return names;
        Map<String, Look> all = new HashMap<>();
        for (String place : PLACES) all.put(place, new Look(PLACE_COLOR, false, false));
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String n = Component.translatable(item.getDescriptionId()).getString().toLowerCase(Locale.ROOT);
            if (n.length() < 3 || SKIP.contains(n) || n.startsWith("item.") || n.startsWith("block.")) continue;
            try {
                ItemStack stack = item.getDefaultInstance();
                all.putIfAbsent(n, new Look(itemColor(stack), stack.hasFoil(), true));
            } catch (RuntimeException e) {
                all.putIfAbsent(n, new Look(PLACE_COLOR, false, true)); // a mod's item that can't make a stack here
            }
        }
        if (mc.level != null) {
            mc.level.registryAccess().registry(Registries.BIOME).ifPresent(reg -> reg.keySet().forEach(id -> {
                String n = Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString()
                        .toLowerCase(Locale.ROOT);
                if (!n.startsWith("biome.")) all.putIfAbsent(n, new Look(PLACE_COLOR, false, false));
            }));
        }
        List<String> sorted = new ArrayList<>(all.keySet());
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        looks = all;
        names = sorted;
        language = lang;
        return names;
    }

    /** The item's own name colour if its mod sets one, else its rarity's; common (white) items use the teal. */
    private static int itemColor(ItemStack stack) {
        TextColor own = stack.getHoverName().getStyle().getColor();
        TextColor rarity = stack.getRarity().getStyleModifier().apply(Style.EMPTY).getColor();
        TextColor c = own != null ? own : rarity;
        if (c == null || (c.getValue() & 0xFFFFFF) == 0xFFFFFF) return PLACE_COLOR;
        return 0xFF000000 | c.getValue();
    }

    /** Colours for each character of {@code text}: item and place names in their colour, enchanted ones shimmering. */
    static Marks mask(String text) {
        Marks marks = Marks.none(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : names()) {
            Look look = looks.get(name);
            int from = 0;
            while ((from = lower.indexOf(name, from)) >= 0) {
                int end = from + name.length();
                boolean wordStart = from == 0 || !Character.isLetterOrDigit(lower.charAt(from - 1));
                // allow a plural "s" after the name ("emeralds")
                int stop = end < lower.length() && lower.charAt(end) == 's' ? end + 1 : end;
                boolean wordEnd = stop >= lower.length() || !Character.isLetterOrDigit(lower.charAt(stop));
                if (wordStart && wordEnd && !anyMarked(marks.color(), from, stop)) {
                    int start = from;
                    boolean foil = look.foil();
                    int color = look.color();
                    // "an enchanted iron sword": the word joins the name and the whole thing shimmers
                    if (look.item()) {
                        int e = from - ENCHANTED.length();
                        if (e >= 0 && lower.startsWith(ENCHANTED, e) && (e == 0 || !Character.isLetterOrDigit(lower.charAt(e - 1)))
                                && !anyMarked(marks.color(), e, from)) {
                            start = e;
                            foil = true;
                            // like vanilla, enchanting lifts a common item to rare
                            if (color == PLACE_COLOR) color = RARE_COLOR;
                        }
                    }
                    for (int i = start; i < stop; i++) {
                        marks.color()[i] = color;
                        marks.shimmer()[i] = foil;
                    }
                }
                from = end;
            }
        }
        return marks;
    }

    private static boolean anyMarked(int[] color, int from, int to) {
        for (int i = from; i < to; i++) if (color[i] != 0) return true;
        return false;
    }
}

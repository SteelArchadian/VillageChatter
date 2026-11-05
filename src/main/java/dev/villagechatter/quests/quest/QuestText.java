package dev.villagechatter.quests.quest;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Builds the chat text for quests: villager speech, answer buttons, place descriptions. */
public final class QuestText {
    private QuestText() {}

    private static final String[] DIRECTIONS = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};

    /** While set, villager speech goes into this list (for the dialogue screen) instead of chat. Server thread only. */
    private static java.util.List<Component> capture;

    /** "<Aldric> line" in chat, or the next line in the dialogue box when called inside {@link #captureSpeech}. */
    public static void say(ServerPlayer player, Component speaker, Component line) {
        if (capture != null) {
            capture.add(line);
            return;
        }
        player.sendSystemMessage(Component.literal("<").append(speaker.copy().withStyle(ChatFormatting.GOLD))
                .append("> ").append(line));
    }

    /** Runs {@code action} and returns what villagers said during it, instead of sending it to chat. */
    public static java.util.List<Component> captureSpeech(Runnable action) {
        java.util.List<Component> previous = capture;
        java.util.List<Component> lines = new java.util.ArrayList<>();
        capture = lines;
        try {
            action.run();
        } finally {
            capture = previous;
        }
        return lines;
    }

    public static MutableComponent button(String labelKey, String hoverKey, ChatFormatting color, UUID villager, String choice) {
        return Component.literal("[").append(Component.translatable(labelKey)).append("]")
                .withStyle(Style.EMPTY.withColor(color)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/villagequests answer " + villager + " " + choice))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(hoverKey))));
    }

    public static Component buttons(Component... buttons) {
        MutableComponent line = Component.literal("  ");
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) line.append("  ");
            line.append(buttons[i]);
        }
        return line;
    }

    /** "an old mineshaft", "a cave", "the wilds". */
    public static Component kindName(String kind) {
        return switch (kind) {
            case "cave" -> Component.translatable("villagequests.kind.cave");
            case "forest" -> Component.translatable("villagequests.kind.forest");
            case "surface" -> Component.translatable("villagequests.kind.surface");
            case "errand" -> Component.translatable("villagequests.kind.errand");
            case "job" -> Component.translatable("villagequests.kind.job");
            default -> {
                ResourceLocation id = ResourceLocation.tryParse(kind);
                String path = id == null ? kind : id.getPath();
                String ns = id == null ? "minecraft" : id.getNamespace();
                yield Component.translatableWithFallback("villagequests.structure." + ns + "." + path, path.replace('_', ' '));
            }
        };
    }

    /** "in a cave to the northeast, about 140 blocks away" */
    public static Component placePhrase(String kind, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz) / 10.0) * 10;
        Component dir = Component.translatable("villagequests.dir." + direction(dx, dz));
        return Component.translatable("villagequests.place", kindName(kind), dir, distance);
    }

    /**
     * "around X 310, Z -840, deep underground": a fuzzy area instead of the exact spot, so you still have to look.
     * The offset is stable per spot (same text every time) and the true spot is within ~15 blocks of it.
     */
    public static Component roughCoords(BlockPos pos) {
        long h = pos.asLong() * 0x9E3779B97F4A7C15L;
        int ox = (int) Math.floorMod(h >> 7, 21) - 10, oz = (int) Math.floorMod(h >> 23, 21) - 10;
        int x = Math.round((pos.getX() + ox) / 10.0F) * 10, z = Math.round((pos.getZ() + oz) / 10.0F) * 10;
        String depth = pos.getY() < 0 ? "deep" : pos.getY() < 50 ? "under" : "surface";
        return Component.translatable("villagequests.coords.rough", x, z,
                Component.translatable("villagequests.depth." + depth)).withStyle(ChatFormatting.AQUA);
    }

    public static Component coords(BlockPos pos) {
        return Component.translatable("villagequests.coords", pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.AQUA);
    }

    public static String direction(int dx, int dz) {
        // Minecraft: +x east, +z south. atan2 gives 0 = east, increasing towards south.
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int index = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return DIRECTIONS[index];
    }

    /** "2 days, 5 hours" in in-game time (one day = 24000 ticks, one hour = 1000 ticks). */
    public static Component duration(long ticks) {
        long days = ticks / 24000, hours = (ticks % 24000) / 1000;
        if (days > 0) return Component.translatable("villagequests.time.days_hours", days, hours);
        return Component.translatable("villagequests.time.hours", Math.max(1, hours));
    }
}

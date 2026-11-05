package dev.villagechatter.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.network.ChatterNetwork.DialogueLine;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Piglins talk: greedy, pushy, friendly while you wear gold and keep your hands off their things. Vanilla anger
 * (no gold armor, hitting one, opening a bastion chest) ends the talk with a snap.
 * <ul>
 *   <li>Gold by dialogue: "I'd like to give you some gold" - how many - a moment to admire it - then one roll of the
 *   vanilla piglin barter table per ingot, thrown at your feet like a vanilla barter, and a list of what you got.
 *   Right-clicking with gold in hand, sneaking or dropping gold still barters the vanilla way.</li>
 *   <li>Piglin standing (api.Factions.piglin): up with every bartered ingot (either way), down when one turns on
 *   you or you kill one. Liked: friendlier lines (and, if the config allows, an extra roll now and then). Disliked:
 *   a short fuse - a question too many and they turn on you.</li>
 * </ul>
 */
public final class PiglinTalk {
    private PiglinTalk() {}

    public static final String FREEBIE = "piglin_freebie";
    /** Player -> day they last got something for nothing. */
    private static final Map<UUID, Long> FREEBIES = new HashMap<>();
    public static final String GOLD = "piglin_gold", AMOUNT = "piglin_n_", WAIT = "piglin_wait", BACK = "piglin_back";
    private static final int[] AMOUNTS = {1, 4, 8, 16, 32, 64};
    private static final int SNAP_LINES = 5, FUSE_LINES = 3, ASK_LINES = 3, LOOK_LINES = 3, GIVE_LINES = 4;
    /** A piglin that turned on you costs standing once per this long (ticks). */
    private static final long ANGER_COOLDOWN = 1200;

    /** A barter being looked over: what comes out, and when. */
    private record Barter(Piglin piglin, List<ItemStack> loot, int ingots, long due) {}

    private static final Map<UUID, Barter> PENDING = new HashMap<>();
    /** "player|piglin" -> game time it last turned on that player (for the standing loss). */
    private static final Map<String, Long> ANGERED = new HashMap<>();
    /** Piglins seen admiring (vanilla barters) -> when, so each barter counts once. */
    private static final Map<UUID, Long> ADMIRING = new HashMap<>();
    /** Questions asked this conversation, for the short fuse. */
    private static final Map<UUID, Integer> QUESTIONS = new HashMap<>();

    public static boolean isPiglin(LivingEntity e) {
        return e instanceof Piglin;
    }

    /** Talkable right now: an adult piglin that isn't after you, and you're not handing it gold the vanilla way. */
    static boolean canTalk(Player player, Piglin piglin) {
        if (!ChatterConfig.TALK_TO_PIGLINS.get() || piglin.isBaby()) return false;
        if (loved(player.getMainHandItem())) return false; // the vanilla barter
        return !angryAt(piglin, player);
    }

    /** What piglins love (gold): vanilla's own tag. */
    static boolean loved(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.PIGLIN_LOVED);
    }

    static boolean angryAt(Piglin piglin, Player player) {
        var angry = piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT);
        if (angry.isPresent() && angry.get().equals(player.getUUID())) return true;
        return piglin.getTarget() == player;
    }

    static boolean hasGold(Player player) {
        return PiglinAi.isWearingGold(player) || player.getInventory().contains(s -> loved(s));
    }

    static int flags(ServerPlayer player) {
        int flags = hasGold(player) ? ChatterNetwork.FLAG_GOLD : 0;
        double standing = FactionBook.piglin(player);
        if (standing >= ChatterConfig.PIGLIN_LIKED_AT.get()) flags |= ChatterNetwork.FLAG_LIKED;
        if (standing <= ChatterConfig.PIGLIN_DISLIKED_AT.get()) flags |= ChatterNetwork.FLAG_DISLIKED;
        return flags;
    }

    public static List<String> options(ServerPlayer player) {
        QUESTIONS.remove(player.getUUID());
        return base(player);
    }

    private static List<String> base(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        if (freebieReady(player)) out.add(FREEBIE);
        if (gold(player) > 0) out.add(GOLD);
        out.add(ConversationManager.GIFT);
        out.addAll(dev.villagechatter.reputation.faction.Sabotage.options(player, dev.villagechatter.api.Factions.Faction.PIGLIN));
        out.add("smalltalk");
        out.add(DialogueAction.GOODBYE);
        return out;
    }

    static int gold(Player player) {
        return player.getInventory().countItem(Items.GOLD_INGOT);
    }

    // ------------------------------------------------------------------ the gold barter

    /** @return true if this was a piglin action (handled here). */
    static boolean handle(ServerPlayer player, Piglin piglin, String action) {
        if (action.equals(GOLD)) {
            int have = gold(player);
            if (have <= 0) {
                say(player, piglin, "nogold", Component.translatable(line(piglin, "villagechatter.piglin.nogold.", 2)), base(player), Map.of());
                return true;
            }
            List<String> options = new ArrayList<>();
            Map<String, Component> labels = new LinkedHashMap<>();
            int max = Math.min(have, 64);
            for (int n : AMOUNTS) if (n < max) amount(options, labels, n);
            amount(options, labels, max);
            options.add(BACK);
            say(player, piglin, "piglin_ask", Component.translatable(line(piglin, "villagechatter.piglin.how_many.", ASK_LINES)), options, labels);
            return true;
        }
        if (action.equals(FREEBIE)) {
            if (!freebieReady(player)) return true;
            FREEBIES.put(player.getUUID(), player.server.overworld().getGameTime() / 24000L);
            List<ItemStack> loot = roll(player, piglin, 1);
            PENDING.put(player.getUUID(), new Barter(piglin, loot, 0, player.server.overworld().getGameTime() + 10));
            say(player, piglin, "piglin_look", Component.translatable(line(piglin, "villagechatter.piglin.freebie.", 3)), List.of(WAIT), Map.of());
            return true;
        }
        if (action.equals(BACK)) {
            say(player, piglin, "piglin_back", Component.translatable(line(piglin, "villagechatter.piglin.back.", 2)), base(player), Map.of());
            return true;
        }
        if (action.startsWith(AMOUNT)) {
            int n;
            try {
                n = Integer.parseInt(action.substring(AMOUNT.length()));
            } catch (NumberFormatException e) {
                return true;
            }
            if (PENDING.containsKey(player.getUUID())) return true;
            n = Math.min(n, Math.min(64, gold(player)));
            if (n <= 0) return true;
            List<ItemStack> loot = barter(player, piglin, n);
            PENDING.put(player.getUUID(), new Barter(piglin, loot, n,
                    player.server.overworld().getGameTime() + ChatterConfig.PIGLIN_ADMIRE_TICKS.get()));
            piglin.playSound(SoundEvents.PIGLIN_ADMIRING_ITEM, 1.0F, piglin.getVoicePitch());
            say(player, piglin, "piglin_look", Component.translatable(line(piglin, "villagechatter.piglin.look.", LOOK_LINES)), List.of(WAIT), Map.of());
            return true;
        }
        if (action.equals(WAIT)) return true; // still looking it over; the result comes on its own
        if (action.equals("smalltalk") && fuseBlows(player, piglin)) return true;
        return false;
    }

    private static void amount(List<String> options, Map<String, Component> labels, int n) {
        options.add(AMOUNT + n);
        labels.put(AMOUNT + n, Component.translatable("villagechatter.option.piglin_amount", n));
    }

    /**
     * Takes {@code ingots} gold ingots and rolls the vanilla piglin barter table once for each (plus the odd extra
     * roll for a liked player, if the config allows). Nothing is dropped yet: see {@link #tick}.
     */
    public static List<ItemStack> barter(ServerPlayer player, Piglin piglin, int ingots) {
        int taken = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && taken < ingots; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.GOLD_INGOT)) continue;
            int t = Math.min(s.getCount(), ingots - taken);
            s.shrink(t);
            taken += t;
        }
        inv.setChanged();
        int rolls = taken;
        if (ChatterConfig.PIGLIN_BARTER_BONUS.get() && FactionBook.piglin(player) >= ChatterConfig.PIGLIN_LIKED_AT.get()) {
            for (int i = 0; i < taken; i++) if (piglin.getRandom().nextDouble() < ChatterConfig.PIGLIN_BARTER_BONUS_CHANCE.get()) rolls++;
        }
        FactionBook.adjust(player, Factions.Faction.PIGLIN, taken * ChatterConfig.PIGLIN_BARTER_GAIN.get());
        return roll(player, piglin, rolls);
    }

    /** That many rolls of the vanilla piglin barter table. */
    private static List<ItemStack> roll(ServerPlayer player, Piglin piglin, int rolls) {
        ServerLevel level = (ServerLevel) piglin.level();
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.PIGLIN_BARTERING);
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            loot.addAll(table.getRandomItems(new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, piglin).create(LootContextParamSets.PIGLIN_BARTER)));
        }
        return loot;
    }

    /** Liked enough, and nothing for free yet today. */
    static boolean freebieReady(ServerPlayer player) {
        if (!FactionBook.enabled() || FactionBook.piglin(player) < ChatterConfig.PIGLIN_FREEBIE_AT.get()) return false;
        Long day = FREEBIES.get(player.getUUID());
        return day == null || day != player.server.overworld().getGameTime() / 24000L;
    }

    /** Barters whose admiring is over: throw the goods at the player and say what they are. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (!PENDING.isEmpty()) {
            Iterator<Map.Entry<UUID, Barter>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Barter b = e.getValue();
                if (now < b.due()) continue;
                it.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                hand(player, b);
            }
        }
        if (server.getTickCount() % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) watchVanillaBarters(player, now);
            if (ADMIRING.size() > 256) ADMIRING.values().removeIf(t -> now - t > 1200);
            if (ANGERED.size() > 512) ANGERED.values().removeIf(t -> now - t > ANGER_COOLDOWN);
        }
    }

    private static void hand(ServerPlayer player, Barter b) {
        Piglin piglin = b.piglin();
        boolean here = player != null && piglin.isAlive() && piglin.level() == player.level();
        for (ItemStack stack : b.loot()) {
            if (here) BehaviorUtils.throwItem(piglin, stack, player.position().add(0, 1, 0));
            else if (player != null) player.getInventory().placeItemBackInInventory(stack);
            else if (piglin.isAlive()) piglin.spawnAtLocation(stack);
        }
        if (player == null) return;
        piglin.playSound(SoundEvents.PIGLIN_CELEBRATE, 1.0F, piglin.getVoicePitch());
        Component list = describe(b.loot());
        Component line = Component.translatable(line(piglin, "villagechatter.piglin.give.", GIVE_LINES), list);
        if (ConversationManager.isTalkingTo(player, piglin)) {
            say(player, piglin, "piglin_give", line, base(player), Map.of());
        } else {
            player.displayClientMessage(named(piglin, line), true);
        }
    }

    /** "4 Ender Pearl, 12 Gravel and 1 Fire Resistance Potion" (same items added up, in the order they came). */
    static Component describe(List<ItemStack> loot) {
        Map<Item, int[]> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> first = new HashMap<>();
        for (ItemStack s : loot) {
            counts.computeIfAbsent(s.getItem(), k -> new int[1])[0] += s.getCount();
            first.putIfAbsent(s.getItem(), s);
        }
        MutableComponent out = Component.empty();
        int i = 0;
        for (var e : counts.entrySet()) {
            if (i > 0) out.append(i == counts.size() - 1 ? Component.translatable("villagechatter.piglin.and") : Component.literal(", "));
            out.append(Component.literal(e.getValue()[0] + " ")).append(first.get(e.getKey()).getHoverName().copy().withStyle(ChatFormatting.GOLD));
            i++;
        }
        return counts.isEmpty() ? Component.translatable("villagechatter.piglin.nothing") : out;
    }

    // ------------------------------------------------------------------ standing

    /** Vanilla barters (gold dropped or handed over): a piglin starts admiring gold near you. Counted once each. */
    private static void watchVanillaBarters(ServerPlayer player, long now) {
        if (!FactionBook.enabled()) return;
        for (Piglin p : player.serverLevel().getEntitiesOfClass(Piglin.class, new AABB(player.blockPosition()).inflate(8),
                p -> p.getBrain().hasMemoryValue(MemoryModuleType.ADMIRING_ITEM) && loved(p.getOffhandItem())
                        && p.getOffhandItem().is(Items.GOLD_INGOT))) {
            Long seen = ADMIRING.get(p.getUUID());
            if (seen != null && now - seen < 200) continue;
            ADMIRING.put(p.getUUID(), now);
            Player nearest = p.level().getNearestPlayer(p, 8);
            if (nearest == player) FactionBook.adjust(player, Factions.Faction.PIGLIN, ChatterConfig.PIGLIN_BARTER_GAIN.get());
        }
    }

    /** A piglin turns on a player: any talk ends with a snap. (What it costs is reputation.faction.FactionDeeds.) */
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof AbstractPiglin piglin) || !(event.getNewAboutToBeSetTarget() instanceof ServerPlayer player)) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (piglin instanceof Piglin p && ConversationManager.isTalkingTo(player, p)) snap(player, p);
    }

    /** Called every tick while talking: a piglin that got angry at you ends it. */
    static boolean checkTemper(ServerPlayer player, Piglin piglin) {
        if (!angryAt(piglin, player)) return false;
        snap(player, piglin);
        return true;
    }

    /** The talk is over: a hostile snap above the hotbar, the angry voice, the box closes. */
    static void snap(ServerPlayer player, Piglin piglin) {
        ConversationManager.endScripted(player);
        QUESTIONS.remove(player.getUUID());
        player.displayClientMessage(named(piglin, Component.translatable(line(piglin, "villagechatter.piglin.snap.", SNAP_LINES))), true);
        piglin.playSound(dev.villagechatter.ChatterSounds.piglin("angry"), 1.0F, 1.0F);
    }

    /** Disliked: one question too many and they turn on you. */
    private static boolean fuseBlows(ServerPlayer player, Piglin piglin) {
        if (FactionBook.piglin(player) > ChatterConfig.PIGLIN_DISLIKED_AT.get()) return false;
        int asked = QUESTIONS.merge(player.getUUID(), 1, Integer::sum);
        if (asked < 2) return false;
        ConversationManager.endScripted(player);
        QUESTIONS.remove(player.getUUID());
        player.displayClientMessage(named(piglin, Component.translatable(line(piglin, "villagechatter.piglin.fuse.", FUSE_LINES))), true);
        piglin.playSound(dev.villagechatter.ChatterSounds.piglin("angry"), 1.0F, 1.0F);
        piglin.getBrain().setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, player.getUUID(), 600L);
        piglin.setTarget(player);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static void say(ServerPlayer player, Piglin piglin, String topic, Component line, List<String> options, Map<String, Component> labels) {
        PacketDistributor.sendToPlayer(player, new DialogueLine(piglin.getId(), topic, options,
                new ChatterNetwork.Extras(labels, java.util.Optional.of(line))));
    }

    private static Component named(LivingEntity speaker, Component line) {
        return Component.empty().append(speaker.getDisplayName().copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(line);
    }

    private static String line(LivingEntity speaker, String prefix, int count) {
        return prefix + speaker.getRandom().nextInt(count);
    }
}

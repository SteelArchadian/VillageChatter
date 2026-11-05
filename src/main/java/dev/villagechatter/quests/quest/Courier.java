package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Courier errands: "Could you take these eggs to the farmer in the village to the northeast?"
 *
 * The giver hands you the package (real items, marked with {@link QuestRegistries#PARCEL}) and names a profession in
 * a real neighbouring village you probably haven't been to. Direction and rough distance only, so finding it is part
 * of the trip. Any villager of that profession there takes it, thanks you by the sender's name, and the whole village
 * warms to you because the sender vouched for you. Sometimes they send a letter back for a bonus.
 *
 * Quest fields used: target = the destination village, kind = "village:&lt;profession or any&gt;",
 * count = package size, itemDropped = "delivered; now carrying the reply letter back".
 */
public final class Courier {
    private Courier() {}

    public static final String DELIVER = "parcel", RETURN_REPLY = "parcel_reply";
    private static final TagKey<Structure> VILLAGES = TagKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace("village"));
    private static final int MIN_BLOCKS = 250, MAX_BLOCKS = 1500;
    /** How close to the destination's centre a villager must live to count as "in that village". */
    private static final int NEAR_TARGET = 160;
    private static final double REPLY_CHANCE = 0.4;
    private static final long REPLY_EXTRA_TICKS = 3 * 24000;

    public record Destination(BlockPos pos, @Nullable ResourceLocation profession) {}

    /** How close the player has to get before they "spot" the specific recipient in the destination village. */
    private static final int SPOT_DISTANCE = 128;

    /** Recipient names per quest instance (the recipient's UUID itself lives in {@link ActiveQuest#buddy}). */
    public static final class Names {
        public static final com.mojang.serialization.Codec<Names> CODEC = com.mojang.serialization.Codec
                .unboundedMap(com.mojang.serialization.Codec.STRING, net.minecraft.network.chat.ComponentSerialization.CODEC)
                .xmap(m -> new Names(new java.util.HashMap<>(m)), n -> n.byQuest);
        final java.util.Map<String, Component> byQuest;

        public Names() {
            this(new java.util.HashMap<>());
        }

        private Names(java.util.Map<String, Component> byQuest) {
            this.byQuest = byQuest;
        }
    }

    @Nullable
    public static Component recipientPersonName(ServerPlayer player, ActiveQuest quest) {
        return player.getData(QuestRegistries.COURIER_NAMES).byQuest.get(quest.instance.toString());
    }

    /**
     * Who the package is for, as people say it: "Kevin the Farmer" once the specific villager is known, otherwise
     * "a Farmer" (or "a villager" when anyone will do).
     */
    public static Component recipientLabel(ServerPlayer player, ActiveQuest quest) {
        Component trade = recipientName(recipientOf(quest));
        Component person = recipientPersonName(player, quest);
        return person != null ? Component.translatable("villagequests.courier.named", person, trade)
                : Component.translatable("villagequests.courier.a_profession", trade);
    }

    /** Ties the trip to one specific villager (their death then ends it, with compensation from the sender). */
    static void bind(ServerPlayer player, ActiveQuest quest, Villager recipient, boolean announce) {
        quest.buddy = recipient.getUUID();
        player.getData(QuestRegistries.COURIER_NAMES).byQuest.put(quest.instance.toString(), recipient.getName().copy());
        relabel(player, quest);
        if (announce) {
            player.sendSystemMessage(Component.translatable("villagequests.courier.spotted", recipient.getName(),
                    recipientName(recipientOf(quest))).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** At accept: if the destination happens to be loaded, pick the recipient now so the sender can name them. */
    public static void tryBindNow(ServerPlayer player, ActiveQuest quest) {
        Villager recipient = findRecipient(player.serverLevel(), quest);
        if (recipient != null) bind(player, quest, recipient, false);
    }

    /** Once a second: when the player nears the destination village, they spot the specific recipient. */
    public static void tickPlayer(ServerPlayer player, PlayerQuests quests) {
        for (ActiveQuest quest : quests.quests) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isCourier() || !quest.isActive() || quest.itemDropped || quest.buddy != null) continue;
            if (player.level().dimension() != quest.dimension || !player.blockPosition().closerThan(quest.target, SPOT_DISTANCE)) continue;
            Villager recipient = findRecipient(player.serverLevel(), quest);
            if (recipient != null) bind(player, quest, recipient, true);
        }
    }

    /** The adult villager of the wanted trade living closest to the destination's centre, if any is loaded. */
    @Nullable
    private static Villager findRecipient(ServerLevel level, ActiveQuest quest) {
        if (level.dimension() != quest.dimension || !level.hasChunkAt(quest.target)) return null;
        ResourceLocation wanted = recipientOf(quest);
        Villager best = null;
        for (Villager v : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(quest.target).inflate(NEAR_TARGET))) {
            if (v.isBaby() || !v.isAlive() || v.getUUID().equals(quest.giver)) continue;
            ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession());
            if (wanted != null && !wanted.equals(prof)) continue;
            if (best == null || v.distanceToSqr(quest.target.getCenter()) < best.distanceToSqr(quest.target.getCenter())) best = v;
        }
        return best;
    }

    /** The recipient died before you got there: the trip ends, and the sender owes you something for your time. */
    public static void onVillagerDeath(ServerLevel level, Villager villager) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            for (ActiveQuest quest : List.copyOf(player.getData(QuestRegistries.PLAYER_QUESTS).quests)) {
                if (!quest.isActive() || quest.itemDropped || !villager.getUUID().equals(quest.buddy)) continue;
                QuestDefinition def = QuestDefinitions.get(quest.questId);
                if (def == null || !def.isCourier()) continue;
                QuestManager.failCompensated(player, quest, villager.getName().getString());
            }
        }
    }

    // ------------------------------------------------------------------ accepting

    /** A neighbouring village (preferably one this player hasn't heard of) and who there gets the package. */
    @Nullable
    public static Destination find(ServerLevel level, BlockPos from, QuestDefinition def, ServerPlayer player) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<BlockPos> found = new ArrayList<>();
        registry.getTag(VILLAGES).ifPresent(tag -> {
            for (Holder<Structure> village : tag) {
                for (ChunkPos chunk : StructureScan.candidates(level, village, from, MIN_BLOCKS, MAX_BLOCKS)) {
                    found.add(chunk.getMiddleBlockPosition(from.getY()));
                }
            }
        });
        if (found.isEmpty()) return null;
        Rumors.Told told = player.getData(QuestRegistries.RUMORS_TOLD);
        List<BlockPos> fresh = found.stream().filter(p -> !told.positions.contains(new ChunkPos(p).getMiddleBlockPosition(0).asLong())).toList();
        List<BlockPos> pool = fresh.isEmpty() ? found : fresh;
        BlockPos pick = pool.get(level.random.nextInt(pool.size()));
        // It's no longer a secret, so no villager will "rumor" it later.
        told.positions.add(new ChunkPos(pick).getMiddleBlockPosition(0).asLong());
        ResourceLocation profession = null;
        if (!def.recipients().isEmpty()) {
            List<ResourceLocation> options = new ArrayList<>(def.recipients());
            profession = options.get(level.random.nextInt(options.size()));
        }
        return new Destination(pick, profession);
    }

    public static String kind(Destination destination) {
        return "village:" + (destination.profession() == null ? "any" : destination.profession().toString());
    }

    @Nullable
    public static ResourceLocation recipientOf(ActiveQuest quest) {
        String p = quest.kind.startsWith("village:") ? quest.kind.substring("village:".length()) : "any";
        return p.equals("any") ? null : ResourceLocation.parse(p);
    }

    /** "Farmer", "Florist", or "villager" when anyone will do. */
    public static Component recipientName(@Nullable ResourceLocation profession) {
        return profession == null ? Component.translatable("entity.minecraft.villager")
                : Component.translatable("entity.minecraft.villager." + profession.getPath());
    }

    public static Component direction(BlockPos from, BlockPos to) {
        return Component.translatable("villagequests.dir." + QuestText.direction(to.getX() - from.getX(), to.getZ() - from.getZ()));
    }

    /** Distance the way people say it: to the nearest fifty. */
    public static int roughDistance(BlockPos from, BlockPos to) {
        double d = Math.sqrt(Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2));
        return (int) Math.max(50, Math.round(d / 50) * 50);
    }

    public static ItemStack makePackage(QuestDefinition def, ActiveQuest quest) {
        return makePackage(def, quest, Component.translatable("villagequests.courier.a_profession", recipientName(recipientOf(quest))));
    }

    public static ItemStack makePackage(QuestDefinition def, ActiveQuest quest, Component recipientLabel) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(def.request().item()), quest.count);
        stack.set(QuestRegistries.PARCEL.get(), quest.instance);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("villagequests.courier.package_name",
                def.request().displayName(), quest.giverName).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("villagequests.courier.package_lore", recipientLabel,
                        direction(quest.giverPos, quest.target), roughDistance(quest.giverPos, quest.target))
                        .withStyle(style -> style.withItalic(true).withColor(ChatFormatting.GRAY)))));
        return stack;
    }

    private static ItemStack makeReply(ActiveQuest quest, Component recipient) {
        ItemStack letter = new ItemStack(Items.PAPER);
        letter.set(QuestRegistries.PARCEL.get(), quest.instance);
        letter.set(DataComponents.CUSTOM_NAME, Component.translatable("villagequests.courier.reply_name", quest.giverName)
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
        letter.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("villagequests.courier.reply_lore", recipient)
                .withStyle(style -> style.withItalic(true).withColor(ChatFormatting.GRAY)))));
        return letter;
    }

    // ------------------------------------------------------------------ dialogue hooks

    /** A courier quest this villager can take delivery of (right village, right profession, you have it all). */
    @Nullable
    public static ActiveQuest deliverable(ServerPlayer player, Villager villager) {
        if (villager.isBaby()) return null;
        ResourceLocation profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        for (ActiveQuest quest : player.getData(QuestRegistries.PLAYER_QUESTS).quests) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isCourier() || !quest.isActive() || quest.itemDropped) continue;
            if (quest.giver.equals(villager.getUUID()) || villager.level().dimension() != quest.dimension) continue;
            if (quest.buddy != null) {
                if (!quest.buddy.equals(villager.getUUID())) continue;  // it's for Kevin, not just any farmer
            } else {
                if (!villager.blockPosition().closerThan(quest.target, NEAR_TARGET)) continue;
                ResourceLocation wanted = recipientOf(quest);
                if (wanted != null && !wanted.equals(profession)) continue;
            }
            if (count(player, quest) < quest.count) continue;
            return quest;
        }
        return null;
    }

    /** The giver, when you're back with their friend's reply letter. */
    @Nullable
    public static ActiveQuest replyReady(ServerPlayer player, Villager villager) {
        for (ActiveQuest quest : player.getData(QuestRegistries.PLAYER_QUESTS).fromGiver(villager.getUUID())) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def != null && def.isCourier() && quest.itemDropped && count(player, quest) > 0) return quest;
        }
        return null;
    }

    /** Hand the package over. @return what the recipient says, or null if there was nothing to deliver. */
    @Nullable
    public static Component deliver(ServerPlayer player, Villager villager) {
        ActiveQuest quest = deliverable(player, villager);
        if (quest == null) return null;
        QuestDefinition def = QuestDefinitions.get(quest.questId);
        take(player, quest, quest.count);

        QuestDefinition.Rewards r = def.rewards();
        int emeralds = Mth.nextInt(player.getRandom(), r.emeraldsMin(), Math.max(r.emeraldsMin(), r.emeraldsMax()));
        if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
        if (r.xp() > 0) player.giveExperiencePoints(r.xp());
        // A friend of a friend: the recipient, and their whole village, start out warmer toward you.
        Reputation.addGoodwill(player, villager, r.goodwillGiver(), r.goodwillVillage());
        player.sendSystemMessage(Component.translatable("villagequests.courier.discovered", quest.giverName).withStyle(ChatFormatting.GREEN));

        Component line = Component.translatable(def.variant(def.text().done(), quest.giver), villager.getName(), quest.giverName,
                def.request().displayName());
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        if (player.getRandom().nextDouble() < REPLY_CHANCE) {
            quest.itemDropped = true;
            quest.deadline += REPLY_EXTRA_TICKS;
            QuestManager.give(player, makeReply(quest, villager.getName()));
            line = line.copy().append(" ").append(Component.translatable("villagequests.courier.reply_ask", quest.giverName));
        } else {
            quests.quests.remove(quest);
            QuestManager.removeNotesFor(player, quest);
        }
        QuestManager.lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
        return line;
    }

    /** The reply reaches the sender: a small bonus and a happy giver. */
    @Nullable
    public static Component returnReply(ServerPlayer player, Villager villager) {
        ActiveQuest quest = replyReady(player, villager);
        if (quest == null) return null;
        QuestDefinition def = QuestDefinitions.get(quest.questId);
        take(player, quest, Integer.MAX_VALUE);
        QuestManager.give(player, new ItemStack(Items.EMERALD, Mth.nextInt(player.getRandom(), 1, 3)));
        Reputation.addGoodwill(player, villager, def.rewards().goodwillGiver() + 1.0, def.rewards().goodwillVillage());
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        quests.quests.remove(quest);
        QuestManager.removeNotesFor(player, quest);
        QuestManager.lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
        return Component.translatable("villagequests.courier.reply_thanks", villager.getName());
    }

    // ------------------------------------------------------------------ items

    /** Rewrites the package's tooltip once the recipient's name is known. */
    private static void relabel(ServerPlayer player, ActiveQuest quest) {
        Component label = recipientLabel(player, quest);
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!quest.instance.equals(stack.get(QuestRegistries.PARCEL.get())) || stack.is(Items.PAPER) && quest.itemDropped) continue;
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.translatable("villagequests.courier.package_lore", label,
                            direction(quest.giverPos, quest.target), roughDistance(quest.giverPos, quest.target))
                            .withStyle(style -> style.withItalic(true).withColor(ChatFormatting.GRAY)))));
        }
    }

    /** The trip fell through: the package (or reply letter) becomes ordinary items you can keep and use. */
    public static void unmark(ServerPlayer player, ActiveQuest quest) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!quest.instance.equals(stack.get(QuestRegistries.PARCEL.get()))) continue;
            stack.remove(QuestRegistries.PARCEL.get());
            stack.remove(DataComponents.CUSTOM_NAME);
            stack.remove(DataComponents.LORE);
        }
        inv.setChanged();
    }

    private static int count(ServerPlayer player, ActiveQuest quest) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (quest.instance.equals(stack.get(QuestRegistries.PARCEL.get()))) total += stack.getCount();
        }
        return total;
    }

    private static void take(ServerPlayer player, ActiveQuest quest, int amount) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!quest.instance.equals(stack.get(QuestRegistries.PARCEL.get()))) continue;
            int n = Math.min(amount, stack.getCount());
            stack.shrink(n);
            amount -= n;
        }
        inv.setChanged();
    }
}

package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * Quests inside Village Chatter's dialogue box. The same flow as the chat prompts in {@link QuestManager}, but the
 * villager's lines show in the dialogue (with their voice) and the answers are dialogue options.
 * Option ids start with {@link #PREFIX}; their labels are sent along, since they aren't fixed lang keys.
 */
public final class QuestDialogue {
    private QuestDialogue() {}

    public static final String PREFIX = "quest:";
    private static final String ASK = PREFIX + "ask";

    /** What a conversation opens with: quest options (shown first) and maybe a greeting that replaces the usual one. */
    public record Opening(List<String> options, Map<String, Component> labels, Optional<Component> greeting) {
        static final Opening NONE = new Opening(List.of(), Map.of(), Optional.empty());
    }

    /** The villager's answer to a quest option, and the choices after it. */
    public record Reply(Component line, List<String> options, Map<String, Component> labels) {}

    public static Opening open(ServerPlayer player, Villager villager) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        List<ActiveQuest> fromGiver = quests.fromGiver(villager.getUUID());
        Builder b = new Builder();

        // Someone else died and the giver owes you for your time ("Aw, Kevin died? Here, for your trouble.").
        Component compensation = QuestManager.payCompensation(player, villager);
        if (compensation != null) {
            b.greeting = compensation;
            return b.build();
        }

        // A captive you came to rescue (freeing them happens as you start talking).
        Component rescued = Rescue.talk(player, villager);
        if (rescued != null) {
            b.greeting = rescued;
            return b.build();
        }

        // A courier package for this villager (you're in the right village, talking to the right trade).
        ActiveQuest parcel = Courier.deliverable(player, villager);
        if (parcel != null) {
            b.greeting = Component.translatable("villagequests.courier.greet_recipient");
            b.option(Courier.DELIVER, "parcel");
            return b.build();
        }

        // Back with the reply letter from the courier trip.
        if (Courier.replyReady(player, villager) != null) {
            b.greeting = Component.translatable("villagequests.courier.greet_reply");
            b.option(Courier.RETURN_REPLY, "parcel_reply");
            return b.build();
        }

        // Bringing the heirloom back: that's all they want to talk about.
        for (ActiveQuest quest : fromGiver) {
            int slot = QuestManager.findHeirloom(player, quest.instance);
            if (slot < 0) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            b.greeting = Component.translatable(quest.isActive() ? "villagequests.say.found" : "villagequests.say.found_late", stack.getHoverName());
            b.option(QuestManager.RETURN, "return").option(QuestManager.KEEP, "keep");
            return b.build();
        }

        // They found work since you promised to help, or what you were asked to build is there: thanks and rewards.
        List<Component> said = QuestText.captureSpeech(() -> {
            if (!QuestManager.tryCompleteJob(player, villager)) Build.tryComplete(player, villager);
        });
        if (!said.isEmpty()) {
            b.greeting = said.get(said.size() - 1);
            return b.build();
        }

        // An errand you can hand in now.
        for (ActiveQuest quest : fromGiver) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isErrand() || !quest.isActive() || QuestManager.countMatching(player, def.request()) < quest.count) continue;
            b.greeting = Component.translatable("villagequests.say.errand_ready", QuestManager.requestText(def, quest.count));
            b.option(QuestManager.DELIVER, "deliver").option(QuestManager.LATER, "later");
            return b.build();
        }

        // Giving up ("I can't do this after all"): they're a little disappointed.
        for (ActiveQuest quest : fromGiver) {
            if (quest.isActive() || quest.state == ActiveQuest.State.EXPIRED) {
                b.option(QuestManager.QUIT, "quit_" + Math.floorMod(villager.getUUID().hashCode() >> 3, 3));
                break;
            }
        }

        // Something new to ask.
        if (pendingOffer(player, villager) != null) {
            b.greeting = Component.translatable(villager.isBaby() ? "villagequests.dialogue.greeting.kid" : "villagequests.dialogue.greeting.adult");
            b.optionRaw(ASK, villager.isBaby() ? "ask_kid" : "ask");
        }
        return b.build();
    }

    /** @param base the villager's everyday options (trade, small talk, goodbye), offered again after quest talk. */
    @Nullable
    public static Reply handle(ServerPlayer player, Villager villager, String action, List<String> base) {
        if (action.equals(ASK)) {
            QuestDefinition def = pendingOffer(player, villager);
            if (def == null) return null;
            Component story = QuestManager.story(def, villager, villager.getData(QuestRegistries.GIVER).count());
            Builder b = new Builder().option(QuestManager.ACCEPT, "accept").option(QuestManager.DECLINE, "decline");
            return new Reply(story, b.options, b.labels);
        }

        String choice = action.substring(PREFIX.length());
        List<Component> said = QuestText.captureSpeech(() -> QuestManager.answer(player, villager.getUUID(), choice));
        Component line = said.isEmpty() ? Component.translatable("villagequests.dialogue.hm") : join(said);

        // Back to whatever else there is to talk about (another quest thing, or the usual options).
        Opening next = open(player, villager);
        List<String> options = new ArrayList<>(next.options());
        options.addAll(base);
        return new Reply(line, options, next.labels());
    }

    /**
     * Small talk's surprise: now and then a fresh answer is a rumor, or the villager remembers they could use a hand
     * (a new quest offer, if the village has room for one). Null = ordinary small talk.
     */
    @Nullable
    public static Reply smallTalkHook(ServerPlayer player, Villager villager, List<String> base) {
        if (villager.getRandom().nextDouble() >= QuestConfig.SMALL_TALK_SURPRISE_CHANCE.get()) return null;
        boolean questFirst = villager.getRandom().nextBoolean();
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean quest = questFirst == (attempt == 0);
            if (quest) {
                GiverData giver = villager.getData(QuestRegistries.GIVER);
                boolean free = !giver.hasOffer() && villager.level().getGameTime() >= giver.nextOfferTime()
                        && player.getData(QuestRegistries.PLAYER_QUESTS).fromGiver(villager.getUUID()).isEmpty();
                if (free && QuestManager.tryOffer(villager, null) != null && pendingOffer(player, villager) != null) {
                    Builder b = new Builder().optionRaw(ASK, villager.isBaby() ? "ask_kid" : "ask");
                    List<String> options = new ArrayList<>(b.options);
                    options.addAll(base);
                    String line = villager.isBaby() ? "villagequests.dialogue.remembered.kid" : "villagequests.dialogue.remembered.adult";
                    return new Reply(Component.translatable(line), options, b.labels);
                }
            } else {
                Component rumor = Rumors.tell(player, villager);
                if (rumor != null) return new Reply(rumor, List.of(), Map.of());
            }
        }
        return null;
    }

    /** The quest this villager would ask this player about, if any. */
    @Nullable
    private static QuestDefinition pendingOffer(ServerPlayer player, Villager villager) {
        GiverData giver = villager.getData(QuestRegistries.GIVER);
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        if (!giver.hasOffer() || !quests.fromGiver(villager.getUUID()).isEmpty()) return null;
        if (quests.activeCount() >= QuestConfig.MAX_ACTIVE_QUESTS.get()) return null;
        QuestDefinition def = QuestDefinitions.get(ResourceLocation.parse(giver.offer()));
        if (def == null || !def.givers().allows(villager.isBaby()) || (def.isJob() && QuestManager.hasJob(villager))) return null;
        return def;
    }

    private static Component join(List<Component> lines) {
        if (lines.size() == 1) return lines.get(0);
        var out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(" ");
            out.append(lines.get(i));
        }
        return out;
    }

    private static final class Builder {
        final List<String> options = new ArrayList<>();
        final Map<String, Component> labels = new HashMap<>();
        Component greeting;

        /** A QuestManager answer choice, shown as option "quest:&lt;choice&gt;". */
        Builder option(String choice, String labelKey) {
            return optionRaw(PREFIX + choice, labelKey);
        }

        Builder optionRaw(String id, String labelKey) {
            options.add(id);
            labels.put(id, Component.translatable("villagequests.dialogue.option." + labelKey));
            return this;
        }

        Opening build() {
            return options.isEmpty() && greeting == null ? Opening.NONE : new Opening(options, labels, Optional.ofNullable(greeting));
        }
    }
}

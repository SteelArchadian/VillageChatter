package dev.villagechatter.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.VillageChatter;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.CloseDialogue;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.network.ChatterNetwork.DialogueLine;
import dev.villagechatter.network.ChatterNetwork.OpenDialogue;
import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.QuestDialogue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.trading.Merchant;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ConversationManager {
    private ConversationManager() {}

    /** Reputation (vanilla gossip) below which villagers get short with you. */
    private static final int GRUMPY_REPUTATION = -20;
    /** Conversations auto-end after 5 minutes so nothing can get stuck. */
    private static final int MAX_CONVERSATION_TICKS = 20 * 60 * 5;

    private static final class Conversation {
        final LivingEntity speaker;
        final int startTick;

        Conversation(LivingEntity speaker, int startTick) {
            this.speaker = speaker;
            this.startTick = startTick;
        }
    }

    private static final Map<UUID, Conversation> CONVERSATIONS = new HashMap<>();

    /** Is someone talking with this entity right now? */
    public static boolean isInConversation(net.minecraft.world.entity.Entity entity) {
        for (Conversation c : CONVERSATIONS.values()) if (c.speaker == entity) return true;
        return false;
    }

    /** NPC uuid -> game time it last killed a monster, so guards can brag about it. */
    private static final Map<UUID, Long> RECENT_KILLS = new HashMap<>();
    private static final int VICTORY_TICKS = 20 * 30;
    private static final double DANGER_RADIUS = 16.0;
    /** A fight this close to whoever you're talking to colours what they say. */
    private static final double FIGHT_RADIUS = 16.0;

    // ------------------------------------------------------------------ interaction

    /** Villagers, golems, guards...: LOW priority so mods with their own NPC dialogue (e.g. Bountiful NPC) go first. */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!PetTalk.isPet(event.getTarget())) handleInteract(event);
    }

    /**
     * Pets: HIGHEST priority, ahead of pet mods (Redomesticate, Companion, Doggy Talents...) that also use a
     * standing right-click. Crouching skips the dialogue, so the classic sit/follow and those mods live on crouch.
     */
    public static void onPetInteract(PlayerInteractEvent.EntityInteract event) {
        if (PetTalk.isPet(event.getTarget())) handleInteract(event);
    }

    private static void handleInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getTarget() instanceof LivingEntity target)) return;
        if (!canStartConversation(player, target)) return;

        // Cancel on both sides: the client gets a hand swing, the server skips vanilla's trade screen.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        if (player instanceof ServerPlayer serverPlayer && !dev.villagechatter.life.law.Intercept.tryStop(serverPlayer, target)) {
            start(serverPlayer, target);
        }
    }

    /**
     * A matching spawn egg on a mob that can have young makes a baby (vanilla). Any other egg would just fall through
     * to the mob's own right-click, which for traders is the trade screen, so those still open the talk.
     */
    static boolean spawnsBaby(ItemStack held, LivingEntity target) {
        return held.getItem() instanceof SpawnEggItem egg && egg.spawnsEntity(held, target.getType())
                && target instanceof net.minecraft.world.entity.AgeableMob && !(target instanceof WanderingTrader);
    }

    static boolean canStartConversation(Player player, LivingEntity target) {
        if (player.isSpectator() || !target.isAlive()) return false;
        // Crouch skips the dialogue (always, for pets: that's where the classic sit/follow lives).
        if (player.isShiftKeyDown() && (ChatterConfig.SNEAK_TO_SKIP.get() || PetTalk.isPet(target))) return false;

        // Leave "important" item interactions to vanilla.
        ItemStack held = player.getMainHandItem();
        if (held.is(Items.NAME_TAG) || held.is(Items.LEAD) || spawnsBaby(held, target)) return false;

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (ChatterConfig.EXCLUDED_ENTITIES.get().contains(typeId.toString())) return false;

        if (target instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin) return PiglinTalk.canTalk(player, piglin);
        if (target instanceof net.minecraft.world.entity.monster.AbstractIllager) {
            return dev.villagechatter.reputation.faction.PillagerTalk.canTalk(player, target);
        }
        // Golems: vanilla's quick iron repair still works when you're holding an ingot.
        if (target instanceof IronGolem golem) {
            return ChatterConfig.TALK_TO_IRON_GOLEMS.get() && !held.is(Items.IRON_INGOT) && golem.getTarget() != player;
        }
        // Pets: only your own; crouch = classic sit/follow (checked above); food, collar dye etc. stay vanilla.
        if (PetTalk.isPet(target)) {
            return PetTalk.enabledFor(target) && target instanceof TamableAnimal pet && pet.isTame()
                    && pet.isOwnedBy(player) && !PetTalk.usesItem(pet, held);
        }

        boolean talkable = target.getType().is(VillageChatter.TALKABLE)
                || (ChatterConfig.ALL_VILLAGER_TYPES.get() && target instanceof AbstractVillager);
        if (!talkable) return false;
        if (target instanceof WanderingTrader && !ChatterConfig.TALK_TO_WANDERING_TRADERS.get()) return false;
        if (!(target instanceof AbstractVillager) && typeId.getPath().contains("guard") && !ChatterConfig.TALK_TO_GUARDS.get()) {
            return false;
        }

        if (target instanceof AbstractVillager trader && trader.isTrading()) return false;
        if (target instanceof Villager villager) {
            if (villager.isSleeping()) return false;
            if (villager.isBaby()) return ChatterConfig.TALK_TO_BABY_VILLAGERS.get();
            if (!ChatterConfig.TALK_TO_VILLAGERS.get()) return false;
            ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
            if (ChatterConfig.EXCLUDED_PROFESSIONS.get().contains(prof.toString())) return false;
        }
        return true;
    }

    private static void start(ServerPlayer player, LivingEntity target) {
        start(player, target, 0);
    }

    private static void start(ServerPlayer player, LivingEntity target, int extraFlags) {
        CONVERSATIONS.put(player.getUUID(), new Conversation(target, player.server.getTickCount()));

        int flags = extraFlags;
        String pool;
        String kind;
        List<String> options = new ArrayList<>();
        ChatterNetwork.Extras extras = ChatterNetwork.Extras.NONE;
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (target instanceof Villager villager) {
            kind = ChatterNetwork.KIND_VILLAGER;
            if (villager.isBaby()) {
                pool = "baby";
                flags |= ChatterNetwork.FLAG_BABY;
            } else {
                flags |= ChatterNetwork.FLAG_VILLAGER;
                ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
                pool = "profession." + prof.getNamespace() + "." + prof.getPath();
                if (villager.getPlayerReputation(player) < GRUMPY_REPUTATION) flags |= ChatterNetwork.FLAG_GRUMPY;
            }
            // Quests module: requests, hand-ins and thank-yous come first, and may replace the greeting.
            if (QuestsModule.enabled()) {
                QuestDialogue.Opening quest = QuestDialogue.open(player, villager);
                options.addAll(quest.options());
                extras = new ChatterNetwork.Extras(quest.labels(), quest.greeting());
            }
            // Living villages: walking in on a chat, or a close friend with a surprise for you.
            if (dev.villagechatter.life.AmbientChatter.isChatting(villager)) {
                dev.villagechatter.life.AmbientChatter.interrupt(villager);
                flags |= ChatterNetwork.FLAG_INTERRUPTED;
            }
            if (options.isEmpty() && dev.villagechatter.life.Kindness.friendGiftReady(player, villager)) {
                options.add(dev.villagechatter.life.Kindness.FRIEND_GIFT);
                flags |= ChatterNetwork.FLAG_FRIEND_GIFT;
            }
            options.addAll(villagerOptions(villager));
            if (!villager.isBaby()) options.addAll(dev.villagechatter.reputation.faction.Sabotage.options(player, dev.villagechatter.api.Factions.Faction.VILLAGE));
            if (extras.text().isEmpty() && !villager.isBaby()) {
                var heard = dev.villagechatter.reputation.faction.Hearsay.greeting(player, villager)
                        .or(() -> dev.villagechatter.reputation.faction.Allegiance.greeting(player, villager));
                if (heard.isPresent()) extras = new ChatterNetwork.Extras(extras.labels(), heard);
            }
        } else if (target instanceof IronGolem golem) {
            kind = ChatterNetwork.KIND_GOLEM;
            pool = "entity." + typeId.getNamespace() + "." + typeId.getPath();
            flags |= GolemTalk.flags(player, golem);
            options.addAll(GolemTalk.options(player, golem));
            // Golems that trust you can have a request (a lost villager): the quests module's protector side.
            if (QuestsModule.enabled()) {
                QuestDialogue.Opening quest = dev.villagechatter.quests.quest.GuardQuests.open(player, golem);
                options.addAll(0, quest.options());
                if (!quest.options().isEmpty()) extras = new ChatterNetwork.Extras(quest.labels(), quest.greeting());
            }
        } else if (target instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin) {
            kind = ChatterNetwork.KIND_PIGLIN;
            pool = "entity." + typeId.getNamespace() + "." + typeId.getPath();
            flags |= PiglinTalk.flags(player);
            options.addAll(PiglinTalk.options(player));
        } else if (target instanceof net.minecraft.world.entity.monster.AbstractIllager illager) {
            kind = ChatterNetwork.KIND_NPC;
            pool = "entity." + typeId.getNamespace() + "." + typeId.getPath();
            options.addAll(dev.villagechatter.reputation.faction.PillagerTalk.options(player, illager));
            extras = new ChatterNetwork.Extras(java.util.Map.of(),
                    java.util.Optional.of(dev.villagechatter.reputation.faction.PillagerTalk.greeting(player, illager)));
        } else if (target instanceof TamableAnimal pet && PetTalk.isPet(pet)) {
            kind = ChatterNetwork.KIND_PET;
            // Modded dogs use the dog lines and sounds.
            pool = PetTalk.isDog(pet) ? "entity.minecraft.wolf" : "entity." + typeId.getNamespace() + "." + typeId.getPath();
            flags |= PetTalk.flags(pet);
            options.addAll(PetTalk.options(pet));
        } else {
            kind = ChatterNetwork.KIND_NPC;
            pool = "entity." + typeId.getNamespace() + "." + typeId.getPath();
            boolean canTrade = target instanceof Merchant merchant && !merchant.getOffers().isEmpty();
            // Non-merchants (guards etc.) still get a way to reach their normal right-click behaviour.
            if (canTrade) options.add(DialogueAction.TRADE);
            if (target instanceof WanderingTrader && dev.villagechatter.quests.quest.TraderRumors.enabled()) {
                options.add(dev.villagechatter.quests.quest.TraderRumors.OPTION);
            }
            else if (!(target instanceof AbstractVillager) && dev.villagechatter.life.GuardTrust.trusts(player, target)) {
                // For a guard, that's their gear: only once they trust you. With law and order on you hand gear over
                // through the gift picker instead of rummaging in their kit.
                options.add(ChatterConfig.LAW_AND_ORDER.get() && dev.villagechatter.life.GuardTrust.isGuard(target) ? GIFT : DialogueAction.INTERACT);
            }
            options.add("smalltalk");
            if (target instanceof Mob hireable) {
                List<String> companion = dev.villagechatter.life.law.Companion.options(player, hireable);
                if (!companion.isEmpty()) {
                    options.remove(DialogueAction.INTERACT);
                    options.remove(GIFT);
                    options.addAll(0, companion);
                    if (companion.contains(DialogueAction.INTERACT)) options.add(1, GIFT);
                    extras = new ChatterNetwork.Extras(dev.villagechatter.life.law.Companion.labels(player, hireable), java.util.Optional.empty());
                }
            }
            if (dev.villagechatter.life.GuardEscort.isWatching(target, player)) flags |= ChatterNetwork.FLAG_WATCHED;
            // Guards can have requests (a sword, a shield, beds...): the quests module's guard side.
            if (QuestsModule.enabled() && dev.villagechatter.quests.quest.GuardQuests.isGuard(target) && target instanceof Mob guard) {
                QuestDialogue.Opening quest = dev.villagechatter.quests.quest.GuardQuests.open(player, guard);
                options.addAll(0, quest.options());
                extras = new ChatterNetwork.Extras(quest.labels(), quest.greeting());
            }
            if (extras.text().isEmpty() && dev.villagechatter.life.GuardTrust.isGuard(target)) {
                var heard = dev.villagechatter.reputation.faction.Hearsay.greeting(player, target)
                        .or(() -> dev.villagechatter.reputation.faction.Allegiance.greeting(player, target));
                if (heard.isPresent()) extras = new ChatterNetwork.Extras(extras.labels(), heard);
            }
        }
        if (target.level().isNight()) flags |= ChatterNetwork.FLAG_NIGHT;
        if (target.level().isRaining()) flags |= ChatterNetwork.FLAG_RAIN;
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) flags |= ChatterNetwork.FLAG_HERO;
        if (!kind.equals(ChatterNetwork.KIND_PET)) flags |= situationFlags(target, player);
        if (!options.contains(DialogueAction.GOODBYE)) options.add(DialogueAction.GOODBYE);

        PacketDistributor.sendToPlayer(player, new OpenDialogue(target.getId(), pool, flags, kind, options, extras));
    }

    /** A villager's everyday choices: trade (if they have a shop) and small talk. Goodbye is added on top. */
    /** Opens the gift picker on the client (DialogueScreen), not a server action. See reputation.Gifts. */
    public static final String GIFT = "gift";

    public static List<String> villagerOptions(Villager villager) {
        List<String> options = new ArrayList<>();
        if (villager.isBaby()) {
            options.add("kid_smalltalk");
            options.add(GIFT);
            if (ChatterConfig.JOB_ASSIGNMENT.get()) options.add(dev.villagechatter.life.job.JobAssign.OPTION);
        } else {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            if (profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT && !villager.getOffers().isEmpty()) {
                options.add(DialogueAction.TRADE);
            }
            options.add("smalltalk");
            Sightings.Kind sighting = Sightings.kindFor(villager);
            if (sighting != null) options.add(sighting.option);
            options.add(GIFT);
            if (ChatterConfig.JOB_ASSIGNMENT.get()) options.add(dev.villagechatter.life.job.JobAssign.OPTION);
        }
        return options;
    }

    // ------------------------------------------------------------------ scripted conversations (life.law)

    /**
     * Law and order runs its own conversations (an arrest, a friend's word, raid banter): the speaker starts them,
     * and every choice goes to {@link dev.villagechatter.life.law.Enforcement}. Esc counts as walking away.
     */
    public static void openScripted(ServerPlayer player, LivingEntity speaker, List<String> options,
                                    java.util.Map<String, Component> labels, Component line) {
        Conversation old = CONVERSATIONS.put(player.getUUID(), new Conversation(speaker, player.server.getTickCount()));
        if (old != null && old.speaker != speaker) PacketDistributor.sendToPlayer(player, new CloseDialogue(old.speaker.getId()));
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(speaker.getType());
        String kind = speaker instanceof Villager ? ChatterNetwork.KIND_VILLAGER : ChatterNetwork.KIND_NPC;
        String pool = speaker instanceof Villager v && v.isBaby() ? "baby" : "entity." + typeId.getNamespace() + "." + typeId.getPath();
        int flags = speaker instanceof Villager v ? (v.isBaby() ? ChatterNetwork.FLAG_BABY : ChatterNetwork.FLAG_VILLAGER) : 0;
        PacketDistributor.sendToPlayer(player, new OpenDialogue(speaker.getId(), pool, flags, kind, options,
                new ChatterNetwork.Extras(labels, java.util.Optional.of(line))));
    }

    public static void scriptedLine(ServerPlayer player, LivingEntity speaker, List<String> options,
                                    java.util.Map<String, Component> labels, Component line) {
        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "law", options,
                new ChatterNetwork.Extras(labels, java.util.Optional.of(line))));
    }

    public static void endScripted(ServerPlayer player) {
        Conversation convo = CONVERSATIONS.remove(player.getUUID());
        if (convo != null) PacketDistributor.sendToPlayer(player, new CloseDialogue(convo.speaker.getId()));
    }

    public static void onAction(ServerPlayer player, DialogueAction action) {
        Conversation convo = CONVERSATIONS.get(player.getUUID());
        if (convo == null || convo.speaker.getId() != action.entityId()) return;
        LivingEntity speaker = convo.speaker;
        if (dev.villagechatter.life.law.Intercept.owns(player, speaker)) {
            if (action.action().equals(DialogueAction.GOODBYE)) CONVERSATIONS.remove(player.getUUID());
            dev.villagechatter.life.law.Intercept.onAction(player, speaker, action.action());
            return;
        }
        if (dev.villagechatter.life.law.Enforcement.owns(player, speaker)) {
            if (action.action().equals(DialogueAction.GOODBYE)) CONVERSATIONS.remove(player.getUUID());
            dev.villagechatter.life.law.Enforcement.onAction(player, speaker, action.action());
            return;
        }

        if (dev.villagechatter.reputation.faction.RaidOutcome.handle(player, speaker, action.action())) return;
        if (dev.villagechatter.life.WorldEvents.handle(player, speaker, action.action())) {
            if (action.action().equals(DialogueAction.GOODBYE)) CONVERSATIONS.remove(player.getUUID());
            return;
        }
        if (dev.villagechatter.reputation.faction.Sabotage.handle(player, speaker, action.action())) return;
        if (dev.villagechatter.reputation.faction.PillagerTalk.handle(player, speaker, action.action())) return;
        if (speaker instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin && !action.action().equals(DialogueAction.GOODBYE)
                && PiglinTalk.handle(player, piglin, action.action())) {
            return;
        }

        switch (action.action()) {
            case DialogueAction.TRADE, DialogueAction.INTERACT -> {
                CONVERSATIONS.remove(player.getUUID());
                if (!stillValid(player, convo)) return;
                if (!dev.villagechatter.life.GuardTrust.trusts(player, speaker)) {
                    dev.villagechatter.life.GuardTrust.refuse(player, speaker);
                    return;
                }
                // Entity#interact runs the entity's own right-click logic without re-firing EntityInteract,
                // so modded traders open whatever screen they normally would.
                player.swing(InteractionHand.MAIN_HAND, true);
                if (action.action().equals(DialogueAction.TRADE) && speaker instanceof AbstractVillager) {
                    // When the trade screen closes, pick the conversation back up ("Need anything else?").
                    RETURN_TO.put(player.getUUID(), speaker);
                    BOUGHT.remove(player.getUUID());
                }
                speaker.interact(player, InteractionHand.MAIN_HAND);
            }
            case DialogueAction.GOODBYE -> {
                CONVERSATIONS.remove(player.getUUID());
                if (speaker instanceof TamableAnimal pet && PetTalk.isPet(pet)) PetTalk.syncSit(pet);
            }
            case "smalltalk", "kid_smalltalk" -> smallTalk(player, speaker);
            case dev.villagechatter.life.job.JobAssign.OPTION -> {
                if (speaker instanceof Villager villager) dev.villagechatter.life.job.JobAssign.openFor(player, villager);
            }
            case dev.villagechatter.life.Kindness.FRIEND_GIFT -> {
                if (speaker instanceof Villager villager) {
                    dev.villagechatter.life.Kindness.giveFriendGift(player, villager);
                    List<String> next = new ArrayList<>(villagerOptions(villager));
                    next.add(DialogueAction.GOODBYE);
                    PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "reply.friend_gift", next));
                }
            }
            case "sight_bees", "sight_sheep", "sight_game" -> {
                if (speaker instanceof Villager villager) {
                    Component answer = Sightings.answer(villager, Sightings.byOption(action.action()));
                    PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "sighting", List.of(),
                            new ChatterNetwork.Extras(java.util.Map.of(), java.util.Optional.of(answer))));
                }
            }
            default -> {
                if (action.action().startsWith(QuestDialogue.PREFIX) && QuestsModule.enabled()
                        && dev.villagechatter.quests.quest.GuardQuests.isGiver(speaker) && speaker instanceof Mob guard) {
                    List<String> base = new ArrayList<>();
                    if (guard instanceof IronGolem golem) {
                        base.addAll(GolemTalk.options(player, golem));
                    } else {
                        if (dev.villagechatter.life.GuardTrust.trusts(player, guard)) {
                            base.add(ChatterConfig.LAW_AND_ORDER.get() ? GIFT : DialogueAction.INTERACT);
                        }
                        base.add("smalltalk");
                        base.add(DialogueAction.GOODBYE);
                    }
                    QuestDialogue.Reply quest = dev.villagechatter.quests.quest.GuardQuests.handle(player, guard, action.action(), base);
                    if (quest != null) {
                        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "quest", quest.options(),
                                new ChatterNetwork.Extras(quest.labels(), java.util.Optional.of(quest.line()))));
                    }
                    return;
                }
                if (action.action().startsWith(QuestDialogue.PREFIX) && speaker instanceof Villager villager && QuestsModule.enabled()) {
                    List<String> base = new ArrayList<>(villagerOptions(villager));
                    base.add(DialogueAction.GOODBYE);
                    QuestDialogue.Reply quest = QuestDialogue.handle(player, villager, action.action(), base);
                    if (quest != null) {
                        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "quest", quest.options(),
                                new ChatterNetwork.Extras(quest.labels(), java.util.Optional.of(quest.line()))));
                    }
                    return;
                }
                if (action.action().startsWith("trader_") && speaker instanceof WanderingTrader trader) {
                    var rumor = dev.villagechatter.quests.quest.TraderRumors.handle(player, trader, action.action());
                    if (rumor != null) {
                        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "rumor", rumor.options(),
                                new ChatterNetwork.Extras(rumor.labels(), java.util.Optional.of(rumor.line()))));
                    }
                    return;
                }
                if (action.action().startsWith("comp_") && speaker instanceof Mob guard) {
                    Component line = dev.villagechatter.life.law.Companion.handle(player, guard, action.action());
                    if (line != null) {
                        List<String> next = new ArrayList<>(dev.villagechatter.life.law.Companion.options(player, guard));
                        next.add("smalltalk");
                        next.add(DialogueAction.GOODBYE);
                        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "companion", next,
                                new ChatterNetwork.Extras(dev.villagechatter.life.law.Companion.labels(player, guard), java.util.Optional.of(line))));
                    }
                    return;
                }
                PetTalk.Reply reply = null;
                if (speaker instanceof IronGolem golem) reply = GolemTalk.handle(player, golem, action.action());
                else if (speaker instanceof TamableAnimal pet && PetTalk.isPet(pet)) {
                    reply = PetTalk.handle(player, pet, action.action());
                    if (reply != null && PetTalk.ENDS_CONVERSATION.contains(action.action())) {
                        // "Come on, let's go": the chat is over and the pet is free to follow (the client shows its
                        // reaction above the hotbar as the box closes).
                        CONVERSATIONS.remove(player.getUUID());
                        PetTalk.syncSit(pet);
                        return;
                    }
                }
                if (reply != null) {
                    PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), reply.topic(), reply.options()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ small talk pacing

    /** Fresh answers per villager before they get tired of questions; the count resets after PACING_WINDOW. */
    private static final int FRESH_ANSWERS = 3;
    private static final long PACING_WINDOW = 20 * 60 * 5;
    /** "playerUuid|speakerUuid" -> {questions asked, game time of the first one}. */
    private static final Map<String, long[]> PACING = new HashMap<>();

    /**
     * One villager shouldn't run through the whole line pool for you: the first few questions get fresh answers
     * (sometimes a rumor or a request, via the quests module), the next one a "you ask a lot of questions",
     * after that they repeat themselves.
     */
    private static void smallTalk(ServerPlayer player, LivingEntity speaker) {
        String topic = smallTalkTopic(player, speaker);
        if (!topic.equals("smalltalk")) {
            PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), topic, List.of()));
            return;
        }
        if (speaker instanceof Villager villager && QuestsModule.enabled()) {
            List<String> base = new ArrayList<>(villagerOptions(villager));
            base.add(DialogueAction.GOODBYE);
            QuestDialogue.Reply hook = QuestDialogue.smallTalkHook(player, villager, base);
            if (hook != null) {
                PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "smalltalk", hook.options(),
                        new ChatterNetwork.Extras(hook.labels(), java.util.Optional.of(hook.line()))));
                return;
            }
        }
        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "smalltalk", List.of()));
    }

    /** Counts this question: "smalltalk" for the first few, then "tired" once, then "repeat". */
    static String smallTalkTopic(ServerPlayer player, LivingEntity speaker) {
        long now = speaker.level().getGameTime();
        String key = player.getUUID() + "|" + speaker.getUUID();
        long[] pace = PACING.get(key);
        if (pace == null || now - pace[1] > PACING_WINDOW) {
            pace = new long[] {0, now};
            PACING.put(key, pace);
        }
        pace[0]++;
        if (pace[0] <= FRESH_ANSWERS) return "smalltalk";
        return pace[0] == FRESH_ANSWERS + 1 ? "tired" : "repeat";
    }

    /** What's going on around the speaker right now. */
    private static int situationFlags(LivingEntity speaker, ServerPlayer player) {
        int flags = 0;
        if (fightNear(speaker)) {
            flags |= ChatterNetwork.FLAG_COMBAT;
            // A guard mid-fight with someone who sides with the raiders (their banner, or their ally) has words for them.
            if (dev.villagechatter.life.GuardTrust.isGuard(speaker) && pillagerSide(player)) flags |= ChatterNetwork.FLAG_BETRAYER;
        }
        boolean monstersNear = !speaker.level().getEntitiesOfClass(Monster.class,
                speaker.getBoundingBox().inflate(DANGER_RADIUS), LivingEntity::isAlive).isEmpty();
        if (monstersNear) flags |= ChatterNetwork.FLAG_DANGER;
        // You look half-dead: villagers and guards notice (pets use the same flag for their own injuries).
        if ((speaker instanceof net.minecraft.world.entity.npc.Villager || dev.villagechatter.life.GuardTrust.isGuard(speaker))
                && player.getHealth() < player.getMaxHealth() * 0.4F) flags |= ChatterNetwork.FLAG_HURT;
        Long killedAt = RECENT_KILLS.get(speaker.getUUID());
        if (killedAt != null && speaker.level().getGameTime() - killedAt < VICTORY_TICKS) {
            flags |= ChatterNetwork.FLAG_VICTORY;
        }
        return flags;
    }

    /**
     * A fight within reach of the speaker: it is fighting a monster itself, a raid is on here, or something hostile
     * nearby is after someone (or a guard or golem is after something).
     */
    static boolean fightNear(LivingEntity speaker) {
        if (speaker instanceof Mob mob && mob.getTarget() instanceof Enemy && mob.getTarget().isAlive()) return true;
        if (speaker.level() instanceof net.minecraft.server.level.ServerLevel level) {
            var raid = level.getRaidAt(speaker.blockPosition());
            if (raid != null && raid.isActive() && !raid.isBetweenWaves()) return true;
        }
        return !speaker.level().getEntitiesOfClass(Mob.class, speaker.getBoundingBox().inflate(FIGHT_RADIUS),
                m -> m.isAlive() && m.getTarget() != null && m.getTarget().isAlive()
                        && (m instanceof Enemy || (dev.villagechatter.life.GuardTrust.isGuard(m) || m instanceof net.minecraft.world.entity.animal.IronGolem)
                                && m.getTarget() instanceof Enemy)).isEmpty();
    }

    /** Wears the raiders' banner or is their ally. */
    public static boolean pillagerSide(ServerPlayer player) {
        return dev.villagechatter.reputation.faction.FactionBook.enabled()
                && (dev.villagechatter.reputation.faction.BannerLoyalty.wearing(player)
                        || dev.villagechatter.reputation.faction.FactionBook.tier(dev.villagechatter.reputation.faction.FactionBook.pillager(player))
                                == dev.villagechatter.api.Factions.Tier.ALLY);
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        if (event.getSource().getEntity() instanceof LivingEntity killer && !(killer instanceof Player)
                && (killer.getType().is(VillageChatter.TALKABLE) || killer instanceof AbstractVillager)) {
            RECENT_KILLS.put(killer.getUUID(), killer.level().getGameTime());
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void onServerTick(ServerTickEvent.Post event) {
        PetTalk.tick(event.getServer());
        PiglinTalk.tick(event.getServer());
        dev.villagechatter.reputation.faction.Witnessed.tick(event.getServer());
        int now = event.getServer().getTickCount();
        if (now % 200 == 0 && !RECENT_KILLS.isEmpty()) {
            long gameTime = event.getServer().overworld().getGameTime();
            RECENT_KILLS.values().removeIf(t -> gameTime - t > VICTORY_TICKS * 2L);
        }
        if (now % 1200 == 0 && !PACING.isEmpty()) {
            long gameTime = event.getServer().overworld().getGameTime();
            PACING.values().removeIf(p -> gameTime - p[1] > PACING_WINDOW);
        }
        if (CONVERSATIONS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Conversation>> it = CONVERSATIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Conversation> entry = it.next();
            Conversation convo = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!stillValid(player, convo) || now - convo.startTick > MAX_CONVERSATION_TICKS) {
                it.remove();
                PacketDistributor.sendToPlayer(player, new CloseDialogue(convo.speaker.getId()));
                dev.villagechatter.life.law.Enforcement.walkedOff(player, convo.speaker);
                continue;
            }
            if (convo.speaker instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin && PiglinTalk.checkTemper(player, piglin)) {
                continue; // snap() already ended it
            }
            holdStill(convo.speaker, player);
        }
    }

    // ------------------------------------------------------------------ back to the conversation after trading

    private static final Map<UUID, LivingEntity> RETURN_TO = new HashMap<>();
    private static final java.util.Set<UUID> BOUGHT = new java.util.HashSet<>();

    public static void onTrade(net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RETURN_TO.containsKey(player.getUUID())) BOUGHT.add(player.getUUID());
    }

    /** The trade screen closed: "Pleasure doing business! Need anything else?" (or, if you only browsed, "Nothing catch your eye?"). */
    public static void onTradeScreenClosed(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getContainer() instanceof net.minecraft.world.inventory.MerchantMenu)) return;
        LivingEntity trader = RETURN_TO.remove(player.getUUID());
        boolean bought = BOUGHT.remove(player.getUUID());
        if (trader == null || !trader.isAlive() || trader.level() != player.level()) return;
        double max = ChatterConfig.MAX_TALK_DISTANCE.get();
        if (trader.distanceToSqr(player) > max * max || (trader instanceof AbstractVillager v && v.isTrading())) return;
        start(player, trader, bought ? ChatterNetwork.FLAG_AFTER_TRADE : ChatterNetwork.FLAG_BROWSED);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RETURN_TO.remove(event.getEntity().getUUID());
        BOUGHT.remove(event.getEntity().getUUID());
        Conversation convo = CONVERSATIONS.remove(event.getEntity().getUUID());
        if (convo != null && event.getEntity() instanceof ServerPlayer player) {
            dev.villagechatter.life.law.Enforcement.walkedOff(player, convo.speaker); // logging out of an arrest is running
        }
    }

    private static boolean stillValid(ServerPlayer player, Conversation convo) {
        Entity speaker = convo.speaker;
        double max = ChatterConfig.MAX_TALK_DISTANCE.get();
        return speaker.isAlive() && !speaker.isRemoved() && speaker.level() == player.level()
                && speaker.distanceToSqr(player) <= max * max;
    }

    public static boolean inConversation(ServerPlayer player) {
        return CONVERSATIONS.containsKey(player.getUUID());
    }

    public static boolean isTalkingTo(ServerPlayer player, LivingEntity speaker) {
        Conversation convo = CONVERSATIONS.get(player.getUUID());
        return convo != null && convo.speaker == speaker;
    }

    /** Keep the NPC facing the player and stop it wandering off mid-sentence. */
    public static void holdStill(LivingEntity speaker, Player player) {
        if (PetTalk.isGoingHome(speaker) || PetTalk.isDoingTrick(speaker)) return;
        if (speaker instanceof Villager villager) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
        }
        if (speaker instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(player);
        }
    }
}

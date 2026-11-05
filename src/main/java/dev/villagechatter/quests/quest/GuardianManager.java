package dev.villagechatter.quests.quest;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.QuestsModule;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Spawns, tracks and removes heirloom guardians. A guardian only exists while its quest owner is online,
 * the quest is running, and the owner has been near it recently.
 */
public final class GuardianManager {
    private GuardianManager() {}

    private static final class Tracked {
        final Mob mob;
        final UUID owner;
        long lastNear;

        Tracked(Mob mob, UUID owner, long lastNear) {
            this.mob = mob;
            this.owner = owner;
            this.lastNear = lastNear;
        }
    }

    /** quest instance id -> live guardian */
    private static final Map<UUID, Tracked> GUARDIANS = new HashMap<>();

    /** Called once a second for each online player. */
    public static void tickPlayer(ServerPlayer player, PlayerQuests quests, long now) {
        int spawnDistance = QuestConfig.GUARDIAN_SPAWN_DISTANCE.get();
        for (ActiveQuest quest : java.util.List.copyOf(quests.quests)) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def != null && def.isRescue()) {
                Rescue.tickPlayer(player, quest, def, quests);
                continue;
            }
            if (def != null && def.isLostPet()) {
                LostPet.tickPlayer(player, quest, def, quests);
                continue;
            }
            if (!quest.isActive() || quest.itemDropped || player.level().dimension() != quest.dimension) continue;
            if (def == null || !def.isHeirloom()) continue;
            if (player.blockPosition().distSqr(quest.target) > (double) spawnDistance * spawnDistance) continue;

            Tracked tracked = GUARDIANS.get(quest.instance);
            if (tracked != null && tracked.mob.isAlive() && !tracked.mob.isRemoved()) {
                tracked.lastNear = now;
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(quest.target)) continue;
            spawn(level, player, quest, now);
        }
    }

    /** Called once a second: removes guardians whose owner left, logged off, or whose quest ended. */
    static void tickAll(MinecraftServer server, long now) {
        long despawnTicks = Math.round(QuestConfig.GUARDIAN_DESPAWN_MINUTES.get() * 60 * 20);
        Iterator<Map.Entry<UUID, Tracked>> it = GUARDIANS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Tracked> entry = it.next();
            Tracked tracked = entry.getValue();
            if (!tracked.mob.isAlive() || tracked.mob.isRemoved()) {
                it.remove();
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(tracked.owner);
            boolean keep = owner != null && questStillWantsGuardian(owner, entry.getKey()) && now - tracked.lastNear <= despawnTicks;
            if (!keep) {
                tracked.mob.discard();
                it.remove();
            }
        }
    }

    private static boolean questStillWantsGuardian(ServerPlayer owner, UUID instance) {
        return owner.getData(QuestRegistries.PLAYER_QUESTS).byInstance(instance)
                .map(q -> q.isActive() && !q.itemDropped).orElse(false);
    }

    private static void spawn(ServerLevel level, ServerPlayer owner, ActiveQuest quest, long now) {
        QuestDefinition def = QuestDefinitions.get(quest.questId);
        if (def == null || def.guardian() == null) return;
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(def.guardian().entity());
        if (type.isEmpty()) {
            QuestsModule.LOGGER.error("Quest {}: unknown guardian entity {}", def.id(), def.guardian().entity());
            return;
        }
        Entity entity = type.get().create(level);
        if (!(entity instanceof Mob mob)) {
            QuestsModule.LOGGER.error("Quest {}: guardian {} is not a mob", def.id(), def.guardian().entity());
            if (entity != null) entity.discard();
            return;
        }

        BlockPos spot = TargetFinder.findSpawnSpot(level, quest.target, 8);
        if (spot == null) spot = quest.target;
        mob.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);

        mob.setCustomName(Component.translatable(def.text().guardianName(), quest.giverName).withStyle(ChatFormatting.RED));
        // Shown when you look right at it, not through walls from across the cave.
        mob.setCustomNameVisible(false);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);

        for (Map.Entry<EquipmentSlot, JsonElement> e : def.guardian().equipment().entrySet()) {
            QuestDefinition.parseStack(e.getValue(), level.registryAccess()).ifPresent(stack -> mob.setItemSlot(e.getKey(), stack));
        }
        // It visibly carries the heirloom; the real one comes from onLivingDrops so the drop is guaranteed.
        def.createItem(level.registryAccess()).ifPresent(stack -> mob.setItemSlot(EquipmentSlot.MAINHAND, stack));
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setDropChance(slot, 0F);

        AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() * def.guardian().healthMultiplier());
            mob.setHealth(mob.getMaxHealth());
        }
        // Undead guardians shouldn't burn up before the player finds them.
        mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        for (QuestDefinition.Effect effect : def.guardian().effects()) {
            BuiltInRegistries.MOB_EFFECT.getHolder(effect.id()).ifPresent(holder ->
                    mob.addEffect(new MobEffectInstance(holder, MobEffectInstance.INFINITE_DURATION, effect.amplifier(), false, false)));
        }

        mob.setData(QuestRegistries.GUARDIAN, new GuardianData(quest.instance, owner.getUUID()));
        level.addFreshEntity(mob);
        GUARDIANS.put(quest.instance, new Tracked(mob, owner.getUUID(), now));
    }

    // ------------------------------------------------------------------ events

    /** Guaranteed heirloom drop, but only while the owner's quest is running. */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        if (!mob.hasData(QuestRegistries.GUARDIAN)) return;
        GuardianData data = mob.getData(QuestRegistries.GUARDIAN);
        if (data.isNone()) return;
        GUARDIANS.remove(data.instance());

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(data.owner());
        if (owner == null) return;
        ActiveQuest quest = owner.getData(QuestRegistries.PLAYER_QUESTS).byInstance(data.instance()).orElse(null);
        if (quest == null || !quest.isActive() || quest.itemDropped) return;
        QuestDefinition def = QuestDefinitions.get(quest.questId);
        if (def == null) return;

        ItemStack heirloom = createHeirloom(def, quest, owner, level);
        if (heirloom == null) return;
        ItemEntity item = new ItemEntity(level, mob.getX(), mob.getY() + 0.5, mob.getZ(), heirloom);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        item.setInvulnerable(true); // lava and cacti shouldn't ruin a quest
        item.setGlowingTag(true);
        event.getDrops().add(item);

        quest.itemDropped = true;
        Component name = heirloom.getHoverName();
        owner.sendSystemMessage(Component.translatable("villagequests.msg.recovered", name, quest.giverName,
                QuestText.duration(Math.max(0, quest.deadline - level.getGameTime()))).withStyle(ChatFormatting.YELLOW));
        level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
    }

    @Nullable
    private static ItemStack createHeirloom(QuestDefinition def, ActiveQuest quest, ServerPlayer owner, ServerLevel level) {
        ItemStack stack = def.createItem(level.registryAccess()).orElse(null);
        if (stack == null) return null;
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(def.text().itemName(), quest.giverName)
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
        stack.set(DataComponents.LORE, new ItemLore(java.util.List.of(
                Component.translatable("villagequests.heirloom.lore", quest.giverName)
                        .withStyle(s -> s.withItalic(true).withColor(ChatFormatting.GRAY)))));
        stack.set(QuestRegistries.HEIRLOOM.get(), new HeirloomData(quest.instance, owner.getUUID()));
        return stack;
    }

    /** Guardians loaded from disk only stay if their quest still wants them; otherwise they quietly vanish. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getEntity() instanceof Mob mob) || event.getLevel().isClientSide()) return;
        if (mob instanceof net.minecraft.world.entity.npc.Villager villager && villager.hasData(QuestRegistries.CAPTIVE)) {
            GuardianData captive = villager.getData(QuestRegistries.CAPTIVE);
            Rescue.onLoaded(villager, event.getLevel().getServer().getPlayerList().getPlayer(captive.owner()));
            return;
        }
        if (!mob.hasData(QuestRegistries.GUARDIAN)) return;
        GuardianData data = mob.getData(QuestRegistries.GUARDIAN);
        if (data.isNone()) return;

        ServerPlayer owner = event.getLevel().getServer().getPlayerList().getPlayer(data.owner());
        boolean wanted = owner != null && questStillWantsGuardian(owner, data.instance()) && !GUARDIANS.containsKey(data.instance());
        if (wanted) {
            GUARDIANS.put(data.instance(), new Tracked(mob, data.owner(), event.getLevel().getGameTime()));
        } else {
            event.setCanceled(true);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        GUARDIANS.clear();
    }

    /** For /villagequests list. */
    static boolean hasLiveGuardian(UUID instance) {
        Tracked t = GUARDIANS.get(instance);
        return t != null && t.mob.isAlive();
    }
}

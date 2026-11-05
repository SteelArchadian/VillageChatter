package dev.villagechatter.reputation.faction;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Pillagers remember how a raid went for you. On their side when they won: praise and standing. On their side when
 * they lost: scorn. Fought them and they won anyway: the captain makes you stand down, and you surrender (they take a
 * toll he names: a few of your things, your armor or your weapon, and they leave you be for a while) or fight on.
 */
public final class RaidOutcome {
    private RaidOutcome() {}

    public static final String SURRENDER = "raid_surrender", FIGHT = "raid_fight";
    /** Surrendered with nothing to pay: not an option the player picks. */
    private static final String POOR = "raid_poor";
    static final int PRAISE = 6, SCORN = 5, GLOAT = 5, TAKEN = 10, DEFIED = 5, DEMAND = 3;
    private static final double REACH = 48;
    /** How long pillagers greet you by the last raid's outcome, and how long a surrender holds (ticks). */
    private static final long MOOD_TICKS = 24000, SPARED_TICKS = 2400, PARLEY_TICKS = 600;

    /** A raid you were near: which side you were on, and whether you killed any of them. */
    private static final class Watch {
        final Raid raid;
        boolean theirSide, fought;

        Watch(Raid raid, boolean theirSide) {
            this.raid = raid;
            this.theirSide = theirSide;
        }
    }

    public enum Mood { PRAISE, SCORN }

    private static final Map<UUID, Watch> WATCHING = new HashMap<>();
    private static final Map<UUID, Mood> MOOD = new HashMap<>();
    private static final Map<UUID, Long> MOOD_UNTIL = new HashMap<>(), SPARED = new HashMap<>(), PARLEY = new HashMap<>();
    /** player -> the captain who made them stand down. */
    private static final Map<UUID, UUID> CAPTAIN = new HashMap<>();
    private static final Map<UUID, Demand> DEMANDS = new HashMap<>();

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0 || !FactionBook.enabled()
                || !ChatterConfig.RAID_OUTCOMES.get()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Watch watch = WATCHING.get(player.getUUID());
        if (watch == null) {
            Raid raid = level.getRaidAt(player.blockPosition());
            if (raid != null && raid.isActive()) WATCHING.put(player.getUUID(), new Watch(raid, ConversationManager.pillagerSide(player)));
            return;
        }
        watch.theirSide |= ConversationManager.pillagerSide(player) && !watch.fought;
        if (!watch.raid.isOver()) return;
        WATCHING.remove(player.getUUID());
        if (watch.raid.isLoss()) {
            if (watch.fought) standDown(player, level);
            else if (watch.theirSide) mood(player, level, Mood.PRAISE, ChatterConfig.RAID_OUTCOME_GAIN.get());
        } else if (watch.raid.isVictory() && watch.theirSide && !watch.fought) {
            mood(player, level, Mood.SCORN, -ChatterConfig.RAID_OUTCOME_LOSS.get());
        }
    }

    /** Killing one of a raid's raiders puts you on the other side of it. */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Raider raider && raider.getCurrentRaid() != null
                && event.getSource().getEntity() instanceof ServerPlayer player) {
            Watch watch = WATCHING.get(player.getUUID());
            if (watch != null && watch.raid == raider.getCurrentRaid()) watch.fought = true;
        }
    }

    private static void mood(ServerPlayer player, ServerLevel level, Mood mood, double standing) {
        MOOD.put(player.getUUID(), mood);
        MOOD_UNTIL.put(player.getUUID(), level.getGameTime() + MOOD_TICKS);
        FactionBook.adjust(player, Faction.PILLAGER, standing);
        Raider near = nearest(player, level);
        String key = mood == Mood.PRAISE ? "villagechatter.raid.praise." + level.random.nextInt(PRAISE)
                : "villagechatter.raid.scorn." + level.random.nextInt(SCORN);
        Component speaker = near != null ? near.getName() : Component.translatable("villagechatter.raid.word");
        player.sendSystemMessage(Component.empty().append(speaker.copy().withStyle(ChatFormatting.GOLD)).append(": ")
                .append(Component.translatable(key)));
    }

    /** How pillagers feel about you after the last raid (for their greetings), or null. */
    public static Mood mood(ServerPlayer player) {
        Long until = MOOD_UNTIL.get(player.getUUID());
        return until != null && until > player.serverLevel().getGameTime() ? MOOD.get(player.getUUID()) : null;
    }

    /** They won and you fought them: the captain (or whoever's left nearest) has you stand down. */
    private static void standDown(ServerPlayer player, ServerLevel level) {
        Raider captain = level.getEntitiesOfClass(Raider.class, player.getBoundingBox().inflate(REACH), LivingEntity::isAlive).stream()
                .min(Comparator.comparing((Raider r) -> !PillagerTalk.isCaptain(r)).thenComparingDouble(r -> r.distanceToSqr(player)))
                .orElse(null);
        if (captain == null) return;
        CAPTAIN.put(player.getUUID(), captain.getUUID());
        Demand demand = demand(player, level);
        if (demand == null) {
            // Nothing on you worth taking: no terms to offer.
            handle(player, captain, POOR);
            return;
        }
        DEMANDS.put(player.getUUID(), demand);
        PARLEY.put(player.getUUID(), level.getGameTime() + PARLEY_TICKS);
        calm(player, level);
        Component line = Component.empty().append(Component.translatable("villagechatter.raid.gloat." + level.random.nextInt(GLOAT)))
                .append(" ").append(Component.translatable("villagechatter.raid.demand." + demand.kind.key + "." + level.random.nextInt(DEMAND),
                        demand.names(player)));
        ConversationManager.openScripted(player, captain, List.of(SURRENDER, FIGHT),
                Map.of(SURRENDER, Component.translatable("villagechatter.option.raid_surrender"),
                        FIGHT, Component.translatable("villagechatter.option.raid_fight")), line);
    }

    /** What the captain wants for letting you live (user, 11-05): a few of your things, your armor, or your weapon. */
    enum TollKind {
        ITEMS("items"), ARMOR("armor"), WEAPON("weapon");

        final String key;

        TollKind(String key) {
            this.key = key;
        }
    }

    /** The captain's terms, named before you choose: the kind, and for ITEMS the inventory slots he picked. */
    record Demand(TollKind kind, List<Integer> slots) {
        /** "your iron sword", "your armor", "your bread, torches and shield". */
        Component names(ServerPlayer player) {
            Inventory inv = player.getInventory();
            return switch (kind) {
                case ARMOR -> Component.translatable("villagechatter.raid.toll.armor");
                case WEAPON -> inv.getSelected().getHoverName();
                case ITEMS -> {
                    net.minecraft.network.chat.MutableComponent out = Component.empty();
                    for (int i = 0; i < slots.size(); i++) {
                        if (i > 0) out.append(i == slots.size() - 1 ? Component.translatable("villagechatter.piglin.and") : Component.literal(", "));
                        out.append(inv.items.get(slots.get(i)).getHoverName());
                    }
                    yield out;
                }
            };
        }
    }

    /** Rolls the captain's terms by the configured weights among what you actually have; null if you have nothing. */
    static Demand demand(ServerPlayer player, ServerLevel level) {
        Inventory inv = player.getInventory();
        List<Integer> filled = new java.util.ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) if (!inv.items.get(i).isEmpty()) filled.add(i);
        boolean armor = inv.armor.stream().anyMatch(s -> !s.isEmpty());
        boolean weapon = isWeapon(inv.getSelected());
        int wItems = filled.isEmpty() ? 0 : ChatterConfig.TOLL_ITEMS_WEIGHT.get();
        int wArmor = armor ? ChatterConfig.TOLL_ARMOR_WEIGHT.get() : 0;
        int wWeapon = weapon ? ChatterConfig.TOLL_WEAPON_WEIGHT.get() : 0;
        int total = wItems + wArmor + wWeapon;
        if (total <= 0) {
            // Every option you qualify for is weighted 0: fall back to whatever you have.
            if (!filled.isEmpty()) wItems = 1;
            else if (armor) wArmor = 1;
            else return null;
            total = wItems + wArmor;
        }
        int roll = level.random.nextInt(total);
        if (roll < wArmor) return new Demand(TollKind.ARMOR, List.of());
        if (roll < wArmor + wWeapon) return new Demand(TollKind.WEAPON, List.of(inv.selected));
        java.util.Collections.shuffle(filled, new java.util.Random(level.random.nextLong()));
        return new Demand(TollKind.ITEMS, List.copyOf(filled.subList(0, Math.min(filled.size(), 1 + level.random.nextInt(3)))));
    }

    static boolean isWeapon(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof net.minecraft.world.item.TieredItem || item instanceof net.minecraft.world.item.ProjectileWeaponItem
                || item instanceof net.minecraft.world.item.TridentItem || item instanceof net.minecraft.world.item.MaceItem;
    }

    /** @return true if this was the captain's stand-down (handled here). */
    public static boolean handle(ServerPlayer player, LivingEntity speaker, String action) {
        if (!speaker.getUUID().equals(CAPTAIN.get(player.getUUID()))) return false;
        ServerLevel level = player.serverLevel();
        if (action.equals(SURRENDER)) {
            CAPTAIN.remove(player.getUUID());
            PARLEY.remove(player.getUUID());
            Demand demand = DEMANDS.remove(player.getUUID());
            Component toll = demand == null ? null : pay(player, demand);
            if (toll == null) {
                CAPTAIN.put(player.getUUID(), speaker.getUUID());
                return handle(player, speaker, POOR);
            }
            SPARED.put(player.getUUID(), level.getGameTime() + SPARED_TICKS);
            calm(player, level);
            ConversationManager.scriptedLine(player, speaker, List.of(DialogueAction.GOODBYE), Map.of(),
                    Component.translatable("villagechatter.raid.taken." + level.random.nextInt(TAKEN), toll));
            return true;
        }
        if (action.equals(FIGHT) || action.equals(DialogueAction.GOODBYE) || action.equals(POOR)) {
            CAPTAIN.remove(player.getUUID());
            PARLEY.remove(player.getUUID());
            DEMANDS.remove(player.getUUID());
            FactionBook.grudge(player);
            ConversationManager.endScripted(player);
            player.sendSystemMessage(Component.empty().append(speaker.getName().copy().withStyle(ChatFormatting.GOLD)).append(": ")
                    .append(Component.translatable(action.equals(POOR) ? "villagechatter.raid.poor"
                            : "villagechatter.raid.defied." + level.random.nextInt(DEFIED))));
            for (Raider r : level.getEntitiesOfClass(Raider.class, player.getBoundingBox().inflate(REACH), LivingEntity::isAlive)) {
                r.addTag(BannerLoyalty.ORDERED_ATTACK); // a holdup roll must not call this off
                r.setTarget(player);
            }
            return true;
        }
        return false;
    }

    /** Hands over what the captain named (whatever of it you still have); null if none of it is left. */
    private static Component pay(ServerPlayer player, Demand demand) {
        Inventory inv = player.getInventory();
        boolean took = false;
        switch (demand.kind) {
            case ARMOR -> {
                for (int i = 0; i < inv.armor.size(); i++) {
                    took |= !inv.armor.get(i).isEmpty();
                    inv.armor.set(i, ItemStack.EMPTY);
                }
            }
            case WEAPON -> {
                took = isWeapon(inv.getSelected());
                if (took) inv.items.set(inv.selected, ItemStack.EMPTY);
            }
            case ITEMS -> {
                for (int slot : demand.slots) {
                    took |= !inv.items.get(slot).isEmpty();
                    inv.items.set(slot, ItemStack.EMPTY);
                }
            }
        }
        if (!took) return null;
        inv.setChanged();
        return Component.translatable("villagechatter.raid.toll." + demand.kind.key);
    }

    /** Raiders around you stop attacking (while they talk, or after you give in). */
    private static void calm(ServerPlayer player, ServerLevel level) {
        for (Raider r : level.getEntitiesOfClass(Raider.class, player.getBoundingBox().inflate(REACH), r -> r.getTarget() == player)) {
            r.setTarget(null);
        }
    }

    /** Raiders leave you be: you're talking terms with their captain, or you surrendered a little while ago. */
    public static boolean spared(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        return SPARED.getOrDefault(player.getUUID(), 0L) > now || PARLEY.getOrDefault(player.getUUID(), 0L) > now;
    }

    private static Raider nearest(ServerPlayer player, ServerLevel level) {
        return level.getEntitiesOfClass(Raider.class, player.getBoundingBox().inflate(REACH), LivingEntity::isAlive).stream()
                .min(Comparator.comparingDouble(r -> r.distanceToSqr(player))).orElse(null);
    }

    /** For /vc raid win|lose: settle the raid you're in now as if it had ended that way. */
    public static boolean settle(ServerPlayer player, boolean pillagersWon) {
        Watch watch = WATCHING.remove(player.getUUID());
        boolean theirSide = watch != null ? watch.theirSide : ConversationManager.pillagerSide(player);
        boolean fought = watch != null && watch.fought;
        ServerLevel level = player.serverLevel();
        if (pillagersWon) {
            if (theirSide && !fought) mood(player, level, Mood.PRAISE, ChatterConfig.RAID_OUTCOME_GAIN.get());
            else standDown(player, level);
        } else {
            if (!theirSide || fought) return false;
            mood(player, level, Mood.SCORN, -ChatterConfig.RAID_OUTCOME_LOSS.get());
        }
        return true;
    }

    public static void forget(UUID player) {
        WATCHING.remove(player);
        CAPTAIN.remove(player);
        PARLEY.remove(player);
        DEMANDS.remove(player);
    }
}

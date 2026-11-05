package dev.villagechatter.life.law;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Kindness the village notices (LAW_AND_ORDER.md §13): monsters killed where the guards or golems can see, saving
 * someone from an attacker, new beds, food tossed to villagers, healing potions, curing zombie villagers.
 * Anything that could be farmed has a daily cap.
 */
public final class GoodDeeds {
    private GoodDeeds() {}

    private static final Map<String, double[]> TODAY = new HashMap<>();

    /** How much of {@code amount} still counts today under {@code cap} for this kind of deed. */
    private static double capped(ServerPlayer player, String kind, double amount, double cap) {
        long day = player.level().getDayTime() / 24000;
        double[] t = TODAY.computeIfAbsent(player.getUUID() + kind, k -> new double[] {day, 0});
        if (t[0] != day) {
            t[0] = day;
            t[1] = 0;
        }
        double ok = Math.max(0, Math.min(amount, cap - t[1]));
        t[1] += ok;
        return ok;
    }

    private static boolean inVillage(ServerPlayer player, Entity where) {
        return Law.enabled() && !player.isCreative() && !player.isSpectator()
                && Witnesses.village(player.serverLevel(), where.blockPosition()).isPresent();
    }

    // ------------------------------------------------------------------ fighting

    /** A monster down: the guards and golems who saw it, and anyone it was after. */
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob foe) || foe.level().isClientSide()) return;
        if (!(Blame.culprit(event.getSource()) instanceof ServerPlayer hunter) || !Law.enabled()) return;
        boolean raider = foe instanceof Raider;
        if (foe instanceof Enemy) Companion.cheer(hunter, raider ? 2 : 0.2);
        if (!inVillage(hunter, foe)) return;

        // Whoever it was going for, or just hit.
        LivingEntity saved = foe.getTarget();
        if (saved == null && foe.tickCount - foe.getLastHurtMobTimestamp() < 100) saved = foe.getLastHurtMob();
        if (saved == hunter || saved != null && !saved.isAlive()) saved = null;
        if (saved instanceof Villager v) {
            Reputation.adjust(hunter, v, 3, false);
            Law.goodDeed(hunter, v, 0.5, 0.5);
            Reputation.addVillageGoodwill(hunter, v, 0.5);
        } else if (saved instanceof IronGolem || saved != null && GuardTrust.isGuard(saved)) {
            boolean golem = saved instanceof IronGolem;
            Law.goodDeed(hunter, saved, golem ? 1.5 : 0.5, golem ? 0.5 : 1.5);
            Reputation.addVillageGoodwill(hunter, saved, 0.3);
        } else if (saved instanceof Player) {
            Reputation.addVillageGoodwill(hunter, saved, 0.3);
        }

        if (!(foe instanceof Enemy)) return;
        // The watch only thanks you for what it saw.
        boolean golemSaw = false, guardSaw = false;
        for (LivingEntity w : Witnesses.of(hunter.serverLevel(), hunter)) {
            if (w instanceof Mob m && Companion.isHired(m)) continue;
            if (w instanceof IronGolem) golemSaw = true;
            else if (GuardTrust.isGuard(w)) guardSaw = true;
        }
        if (!golemSaw && !guardSaw) return;
        double share = raider ? 1 : capped(hunter, "monster", 0.2, 3);
        if (share <= 0) return;
        Law.goodDeed(hunter, foe, golemSaw ? share : 0, guardSaw ? share * 2 : 0);
        Reputation.addVillageGoodwill(hunter, foe, share / 2);
    }

    // ------------------------------------------------------------------ building

    /** A new bed for the village: mostly the villagers' gratitude, a little from the guards. */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var state = event.getPlacedBlock();
        if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT) return;
        if (!inVillage(player, player)) return;
        if (capped(player, "bed", 1, 3) <= 0) return;
        Reputation.addVillageGoodwill(player, player, 0.5);
        Law.goodDeed(player, player, 0, 0.1);
    }

    // ------------------------------------------------------------------ food

    private static final List<Item> FOOD = List.of(Items.BREAD, Items.CARROT, Items.POTATO, Items.BEETROOT);
    private static final double FOOD_EACH = 0.05, FOOD_DAILY = 1.5, REACH = 2.5;

    private static final class Toss {
        final ItemEntity item;
        final UUID player;
        int count;
        Vec3 at;
        boolean thanked;

        Toss(ItemEntity item, UUID player) {
            this.item = item;
            this.player = player;
            this.count = item.getItem().getCount();
            this.at = item.position();
        }
    }

    private static final List<Toss> TOSSES = new ArrayList<>();

    public static void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !FOOD.contains(event.getEntity().getItem().getItem())) return;
        if (!inVillage(player, player)) return;
        TOSSES.add(new Toss(event.getEntity(), player.getUUID()));
    }

    /** Picked back up by a player: that much never went to the villagers. */
    public static void onPickup(ItemEntityPickupEvent.Post event) {
        for (Iterator<Toss> it = TOSSES.iterator(); it.hasNext(); ) {
            Toss t = it.next();
            if (t.item != event.getItemEntity()) continue;
            if (t.item.isRemoved()) it.remove();
            else t.count = t.item.getItem().getCount();
        }
    }

    public static void tick(MinecraftServer server, long now) {
        if (now % 20 != 0 || TOSSES.isEmpty()) return;
        for (Iterator<Toss> it = TOSSES.iterator(); it.hasNext(); ) {
            Toss t = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(t.player);
            ItemEntity e = t.item;
            boolean gone = e.isRemoved();
            int taken = gone ? (e.getRemovalReason() == Entity.RemovalReason.KILLED ? 0 : t.count) : t.count - e.getItem().getCount();
            if (player != null && taken > 0 && e.level() == player.level()) {
                Villager taker = e.level().getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(t.at, t.at).inflate(REACH))
                        .stream().findFirst().orElse(null);
                if (taker != null) {
                    double ok = capped(player, "food", taken * FOOD_EACH, FOOD_DAILY);
                    if (ok > 0) Reputation.addVillageGoodwill(player, taker, ok);
                    if (!t.thanked && ok > 0) {
                        Reputation.adjust(player, taker, 1, false);
                        t.thanked = true;
                    }
                }
            }
            if (!gone) {
                t.count = e.getItem().getCount();
                t.at = e.position();
            }
            if (gone || player == null || e.getAge() > 1200) it.remove();
        }
    }

    // ------------------------------------------------------------------ healing

    /** A healing or regeneration splash (or lingering) potion landing on hurt villagers, guards or golems. */
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.getOwner() instanceof ServerPlayer player)) return;
        PotionContents contents = potion.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        boolean heals = false;
        for (var effect : contents.getAllEffects()) {
            Holder<MobEffect> e = effect.getEffect();
            if (e.is(MobEffects.HEAL) || e.is(MobEffects.REGENERATION)) heals = true;
        }
        if (!heals || !inVillage(player, potion)) return;
        ServerLevel level = player.serverLevel();
        for (LivingEntity who : level.getEntitiesOfClass(LivingEntity.class, potion.getBoundingBox().inflate(4, 2, 4),
                w -> w.isAlive() && w.getHealth() < w.getMaxHealth() && w != player)) {
            if (who instanceof Villager v) {
                Reputation.adjust(player, v, 2, false);
                Reputation.addVillageGoodwill(player, v, capped(player, "heal", 0.3, 3));
            } else if (who instanceof IronGolem) {
                Law.goodDeed(player, who, 1, 0.3);
            } else if (GuardTrust.isGuard(who)) {
                Law.goodDeed(player, who, 0.3, 1);
            } else if (who instanceof Player) {
                Reputation.addVillageGoodwill(player, who, capped(player, "heal", 0.2, 3));
            }
        }
    }

    // ------------------------------------------------------------------ curing

    private static Field conversionStarter;

    /** A zombie villager cured: vanilla already makes the villager love you; the whole village hears of it too. */
    public static void onConvert(LivingConversionEvent.Post event) {
        if (!(event.getEntity() instanceof ZombieVillager zombie) || !(event.getOutcome() instanceof Villager villager)) return;
        if (!(zombie.level() instanceof ServerLevel level)) return;
        UUID curer = curer(zombie);
        if (curer == null || !(level.getPlayerByUUID(curer) instanceof ServerPlayer player) || !inVillage(player, villager)) return;
        Reputation.addVillageGoodwill(player, villager, 3);
        Law.goodDeed(player, villager, 2, 2);
    }

    private static UUID curer(ZombieVillager zombie) {
        try {
            if (conversionStarter == null) {
                conversionStarter = ZombieVillager.class.getDeclaredField("conversionStarter");
                conversionStarter.setAccessible(true);
            }
            return (UUID) conversionStarter.get(zombie);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

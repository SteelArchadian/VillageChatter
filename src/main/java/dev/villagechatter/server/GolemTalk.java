package dev.villagechatter.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Iron golems don't talk, but they listen: grumbles, and they notice poppies and iron you're carrying. */
public final class GolemTalk {
    private GolemTalk() {}

    /** Kindness to a golem only counts toward reputation and XP once per this long, per golem and player. */
    private static final int KINDNESS_COOLDOWN_TICKS = 20 * 60 * 5;
    /**
     * Keeping the village's guardian happy and healthy earns a small bump with every villager nearby: 2 goodwill is one
     * minimal gossip step (about what a single trade earns), so it's felt without being farmable thanks to the cooldown.
     */
    private static final double POPPY_GOODWILL = 2.0, REPAIR_GOODWILL = 2.0;
    private static final int POPPY_XP = 2, REPAIR_XP = 3;

    /** "kind|golemUuid|playerUuid" -> game time that kindness last counted. */
    private static final Map<String, Long> LAST_KINDNESS = new HashMap<>();

    static int flags(ServerPlayer player, IronGolem golem) {
        int flags = 0;
        if (has(player, Items.POPPY)) flags |= ChatterNetwork.FLAG_POPPY;
        if (canRepair(player, golem)) flags |= ChatterNetwork.FLAG_IRON;
        return flags;
    }

    static List<String> options(ServerPlayer player, IronGolem golem) {
        List<String> out = new ArrayList<>();
        if (has(player, Items.POPPY)) out.add("give_poppy");
        if (canRepair(player, golem)) out.add("give_iron");
        out.add("howareyou");
        out.add("quiet");
        out.add("goodbye");
        return out;
    }

    static PetTalk.Reply handle(ServerPlayer player, IronGolem golem, String action) {
        switch (action) {
            case "howareyou", "quiet" -> {}
            case "give_poppy" -> {
                if (!take(player, Items.POPPY)) return new PetTalk.Reply("reply.nothing", options(player, golem));
                golem.offerFlower(true);
                hearts(golem);
                // The village notices you're kind to its guardian.
                reward(player, golem, "poppy", POPPY_GOODWILL, POPPY_XP);
            }
            case "give_iron" -> {
                if (!canRepair(player, golem) || !take(player, Items.IRON_INGOT)) {
                    return new PetTalk.Reply("reply.nothing", options(player, golem));
                }
                golem.heal(25.0F);
                golem.level().playSound(null, golem, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F,
                        1.0F + (golem.getRandom().nextFloat() - golem.getRandom().nextFloat()) * 0.2F);
                sparkles(golem);
                reward(player, golem, "repair", REPAIR_GOODWILL, REPAIR_XP);
            }
            default -> {
                return null;
            }
        }
        return new PetTalk.Reply("reply." + action, options(player, golem));
    }

    /** Goodwill with every villager around and a little XP, at most once per cooldown per golem. */
    private static void reward(ServerPlayer player, IronGolem golem, String kind, double goodwill, int xp) {
        long now = golem.level().getGameTime();
        String key = kind + "|" + golem.getUUID() + "|" + player.getUUID();
        Long last = LAST_KINDNESS.get(key);
        if (last != null && now - last < KINDNESS_COOLDOWN_TICKS) return;
        LAST_KINDNESS.put(key, now);
        Reputation.addVillageGoodwill(player, golem, goodwill);
        dev.villagechatter.life.law.Law.goodDeed(player, golem, goodwill * 2, 0); // anger one golem, anger all: kindness too
        player.giveExperiencePoints(xp);
    }

    private static boolean canRepair(ServerPlayer player, IronGolem golem) {
        return golem.getHealth() < golem.getMaxHealth() && has(player, Items.IRON_INGOT);
    }

    private static boolean has(ServerPlayer player, Item item) {
        return player.getInventory().contains(new ItemStack(item));
    }

    private static boolean take(ServerPlayer player, Item item) {
        if (player.getAbilities().instabuild) return true;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** "He noticed": a few happy sparkles after a repair. */
    private static void sparkles(IronGolem golem) {
        if (golem.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, golem.getX(), golem.getY() + golem.getBbHeight() * 0.6, golem.getZ(),
                    8, 0.5, 0.6, 0.5, 0);
        }
    }

    private static void hearts(IronGolem golem) {
        if (golem.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, golem.getX(), golem.getY() + golem.getBbHeight() + 0.3, golem.getZ(),
                    4, 0.4, 0.2, 0.4, 0);
        }
    }
}

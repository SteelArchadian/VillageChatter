package dev.villagechatter.reputation.faction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions.Faction;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.network.ChatterNetwork.DialogueLine;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Talking to pillagers who tolerate you (Factions v2, item 3). Captains, the banner wearers, are the ringleaders: they
 * talk, and they're the only pillagers who hand out work (sabotage jobs and raids, see {@link Sabotage}). Everyone
 * else says a line, usually something ugly about villagers, and takes a gift (weapons, armor, meat) if you've one.
 * Savage, like Skyrim's bandits and Fallout's raiders.
 */
public final class PillagerTalk {
    private PillagerTalk() {}

    public static final String TALK = "pillager_talk";
    static final int BARKS = 12, TALKS = 6;

    /** Pillagers and vindicators, while they hold a truce with you and aren't after you. */
    public static boolean canTalk(net.minecraft.world.entity.player.Player player, LivingEntity target) {
        if (!ChatterConfig.TALK_TO_PILLAGERS.get() || !(target instanceof Pillager || target instanceof Vindicator)) return false;
        if (!(player instanceof ServerPlayer sp) || !(target instanceof Raider raider)) return false;
        return raider.getTarget() != player && FactionBook.truce(sp, raider);
    }

    /** A captain: leading a patrol, or wearing the ominous banner. */
    public static boolean isCaptain(Raider raider) {
        if (raider.isPatrolLeader()) return true;
        ItemStack head = raider.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && ItemStack.matches(head,
                Raid.getLeaderBannerInstance(raider.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
    }

    public static List<String> options(ServerPlayer player, AbstractIllager illager) {
        List<String> out = new ArrayList<>();
        boolean captain = isCaptain(illager);
        if (captain) out.addAll(Sabotage.options(player, Faction.PILLAGER));
        out.add(dev.villagechatter.server.ConversationManager.GIFT);
        if (captain) out.add(TALK);
        return out;
    }

    /** A captain greets you by your standing on both sides; anyone else just barks a line. */
    public static Component greeting(ServerPlayer player, AbstractIllager illager) {
        RaidOutcome.Mood mood = RaidOutcome.mood(player);
        if (mood != null) {
            return Component.translatable(mood == RaidOutcome.Mood.PRAISE ? "villagechatter.raid.praise." + illager.getRandom().nextInt(RaidOutcome.PRAISE)
                    : "villagechatter.raid.scorn." + illager.getRandom().nextInt(RaidOutcome.SCORN));
        }
        if (isCaptain(illager)) return Allegiance.pillagerGreeting(player);
        return Component.translatable("villagechatter.pillager.bark." + illager.getRandom().nextInt(BARKS));
    }

    /** @return true if this was a pillager action (handled here). */
    public static boolean handle(ServerPlayer player, LivingEntity speaker, String action) {
        if (!action.equals(TALK) || !(speaker instanceof AbstractIllager illager)) return false;
        List<String> next = new ArrayList<>(options(player, illager));
        next.add(DialogueAction.GOODBYE);
        PacketDistributor.sendToPlayer(player, new DialogueLine(speaker.getId(), "smalltalk", next, new ChatterNetwork.Extras(Map.of(),
                Optional.of(Component.translatable("villagechatter.pillager.captain.talk." + illager.getRandom().nextInt(TALKS))))));
        return true;
    }
}

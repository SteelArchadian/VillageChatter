package dev.villagechatter.life;

import java.util.List;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Nitwits are the village beggars. Hand one an emerald or something to eat and they bless you, like Skyrim's Gift of
 * Charity: speech checks go a little better for a few minutes (api.SpeechCheck), and the village thinks a little
 * better of you, up to a few gifts a day. Stored on the player as {day, gifts counted that day, blessed until}.
 */
public final class Charity {
    private Charity() {}

    static final double GOODWILL = 0.5, VILLAGE_SHARE = 0.5;
    static final int LINES = 7;

    public static boolean isBeggar(Villager villager) {
        return !villager.isBaby() && villager.getVillagerData().getProfession() == VillagerProfession.NITWIT;
    }

    public static boolean isAlms(ItemStack stack) {
        return stack.is(Items.EMERALD) || stack.has(DataComponents.FOOD);
    }

    /** Takes one of the stack, blesses the player and returns the beggar's thanks. */
    public static Component give(ServerPlayer player, Villager beggar, ItemStack stack) {
        stack.shrink(1);
        player.getInventory().setChanged();
        long now = player.server.overworld().getGameTime();
        long day = now / 24000L;
        List<Long> c = player.getData(ChatterAttachments.CHARITY);
        long counted = c.get(0) == day ? c.get(1) : 0;
        if (counted < ChatterConfig.CHARITY_PER_DAY.get()) {
            Reputation.addGoodwill(player, beggar, GOODWILL, GOODWILL * VILLAGE_SHARE);
            counted++;
        }
        player.setData(ChatterAttachments.CHARITY, List.of(day, counted, now + ChatterConfig.CHARITY_BLESSING_SECONDS.get() * 20L));
        if (beggar.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getEyeY(), player.getZ(), 6, 0.4, 0.4, 0.4, 0);
        }
        beggar.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, beggar.getVoicePitch());
        return Component.translatable("villagechatter.charity.bless." + beggar.getRandom().nextInt(LINES));
    }

    public static boolean blessed(ServerPlayer player) {
        return player.getData(ChatterAttachments.CHARITY).get(2) > player.server.overworld().getGameTime();
    }
}

package dev.villagechatter.quests.quest;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.SecurityBlocks;
import dev.villagechatter.quests.QuestConfig;
import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Guard "security" requests: shore up the village with N (10-30) defensive blocks. Walls, fences, gates, doors,
 * trapdoors, and modded portcullises, spikes and turrets, as {@link SecurityBlocks} decides. Only blocks you place
 * inside the village while the request is open count; breaking defensive blocks there takes them back off, so
 * placing and breaking the same fence doesn't farm it.
 *
 * Build quests whose requirement is {@code "kind": "placed"} (count rolled from {@code "count": {"min", "max"}}).
 * Offered only once the guard knows you a little (trust tier 1, {@link QuestConfig#SECURITY_MIN_TRUST}). The pay
 * scales with the size: 1 emerald for the smallest job up to 5 for the biggest. Abandoning pays for what you built;
 * abandoning with nothing built pays nothing.
 */
public final class Security {
    private Security() {}

    public static boolean isPlacement(QuestDefinition def) {
        return def.isBuild() && "placed".equals(GsonHelper.getAsString(def.settings(), "kind", ""));
    }

    /** Tier 1 with this guard: they'll trust you with a security job. */
    public static boolean trustedEnough(ServerPlayer player, Entity guard) {
        return player.isCreative() || GuardTrust.score(player, guard) >= QuestConfig.SECURITY_MIN_TRUST.get();
    }

    static int rollCount(QuestDefinition def, net.minecraft.util.RandomSource random) {
        JsonObject c = GsonHelper.getAsJsonObject(def.settings(), "count", new JsonObject());
        int min = GsonHelper.getAsInt(c, "min", 10), max = Math.max(min, GsonHelper.getAsInt(c, "max", 30));
        return Mth.nextInt(random, min, max);
    }

    /** Emeralds for the whole job: scaled across the quest's emerald range by how big it was. */
    static int fullEmeralds(QuestDefinition def, int count) {
        JsonObject c = GsonHelper.getAsJsonObject(def.settings(), "count", new JsonObject());
        int min = GsonHelper.getAsInt(c, "min", 10), max = Math.max(min + 1, GsonHelper.getAsInt(c, "max", 30));
        QuestDefinition.Rewards r = def.rewards();
        double t = Mth.clamp((count - min) / (double) (max - min), 0.0, 1.0);
        return (int) Math.round(r.emeraldsMin() + t * (r.emeraldsMax() - r.emeraldsMin()));
    }

    /** XP scales the same way: the JSON's xp is for a 20-block job. */
    static int fullXp(QuestDefinition def, int count) {
        return (int) Math.round(def.rewards().xp() * count / 20.0);
    }

    /** Pays for the finished job. Goodwill is the guard's (the bigger share) plus a little village standing. */
    static void reward(ServerPlayer player, Mob guard, QuestDefinition def, int count) {
        int emeralds = fullEmeralds(def, count);
        if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
        int xp = fullXp(def, count);
        if (xp > 0) player.giveExperiencePoints(xp);
        QuestDefinition.Rewards r = def.rewards();
        GuardQuests.addGuardGoodwill(player, guard, r.goodwillGiver() * QuestConfig.REPUTATION_MULTIPLIER.get());
        if (r.goodwillVillage() > 0) Reputation.addVillageGoodwill(player, guard, r.goodwillVillage());
        // The other guards hear about it too (the village's shared guard standing).
        dev.villagechatter.life.law.Law.goodDeed(player, guard, 0, r.goodwillVillage());
    }

    /**
     * Abandoning part-way: pay for what was built (rounded down), no disappointment.
     * @return true if anything had been built (so the caller skips the usual "disappointed" dip).
     */
    static boolean payPartial(ServerPlayer player, @Nullable Mob guard, ActiveQuest quest, QuestDefinition def) {
        int built = Math.min(quest.progress, quest.count);
        if (built <= 0) return false;
        double share = built / (double) quest.count;
        int emeralds = (int) Math.floor(fullEmeralds(def, quest.count) * share);
        if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
        int xp = (int) Math.floor(fullXp(def, quest.count) * share);
        if (xp > 0) player.giveExperiencePoints(xp);
        if (guard != null) GuardQuests.addGuardGoodwill(player, guard, def.rewards().goodwillGiver() * share * QuestConfig.REPUTATION_MULTIPLIER.get());
        return true;
    }

    // ------------------------------------------------------------------ counting

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) count(player, event.getPos(), event.getPlacedBlock(), +1);
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) count(player, event.getPos(), event.getState(), -1);
    }

    /** A defensive block placed (+1) or broken (-1) by this player; public for tests. */
    public static void count(ServerPlayer player, BlockPos pos, BlockState state, int delta) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        if (quests.quests.isEmpty()) return;
        boolean checked = false;
        for (ActiveQuest quest : quests.quests) {
            if (!quest.isActive() || quest.dimension != player.level().dimension()) continue;
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !isPlacement(def)) continue;
            double dx = pos.getX() - quest.giverPos.getX(), dz = pos.getZ() - quest.giverPos.getZ();
            if (dx * dx + dz * dz > Reputation.VILLAGE_RADIUS * Reputation.VILLAGE_RADIUS) continue;
            if (!checked) {
                if (!SecurityBlocks.isDefensive(state)) return;
                checked = true;
            }
            int before = quest.progress;
            quest.progress = Mth.clamp(quest.progress + delta, 0, quest.count);
            if (quest.progress == before) continue;
            if (quest.progress == quest.count && delta > 0) {
                player.displayClientMessage(Component.translatable("villagequests.security.done", quest.giverName).withStyle(ChatFormatting.GREEN), true);
            } else {
                player.displayClientMessage(Component.translatable("villagequests.security.progress", quest.progress, quest.count).withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    /** "12/20 placed", for the journal. */
    static Component status(ActiveQuest quest) {
        return Component.translatable("villagequests.journal.status.security", Math.min(quest.progress, quest.count), quest.count);
    }
}

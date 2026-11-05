package dev.villagechatter.quests.quest;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import dev.villagechatter.quests.QuestRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

/**
 * Build requests: a village that lacks something asks you to build it, and the quest checks the real village.
 * Only offered while the thing is actually missing. JSON "requirement" kinds:
 * <ul>
 *   <li>{@code golem}: a player-built iron golem near the giver (offered when the village has no golem at all)</li>
 *   <li>{@code poi}: points of interest like beds ({@code minecraft:home}) or a bell ({@code minecraft:meeting});
 *       {@code "when": "homeless"} offers it when there are more villagers than beds</li>
 *   <li>{@code blocks}: blocks from a tag or one block id (lights, fences, farmland) within a small radius</li>
 * </ul>
 * Targets: {@code "min": N} (at least N in total) or {@code "add": N} (N more than when you accepted).
 */
public final class Build {
    private Build() {}

    private static final int VILLAGE_RADIUS = 48;
    /** Block scans are capped so a talk never reads more than ~120k blocks. */
    private static final int MAX_BLOCK_RADIUS = 24;

    record Requirement(String kind, @Nullable ResourceLocation poi, @Nullable ResourceLocation tag, @Nullable ResourceLocation block,
                       int min, int add, int radius, String when) {}

    static Requirement requirement(QuestDefinition def) {
        JsonObject r = def.build();
        String block = GsonHelper.getAsString(r, "block", "");
        String tag = GsonHelper.getAsString(r, "tag", "");
        String poi = GsonHelper.getAsString(r, "poi", "");
        return new Requirement(GsonHelper.getAsString(r, "kind"),
                poi.isEmpty() ? null : ResourceLocation.parse(poi),
                tag.isEmpty() ? null : ResourceLocation.parse(tag.startsWith("#") ? tag.substring(1) : tag),
                block.isEmpty() ? null : ResourceLocation.parse(block),
                GsonHelper.getAsInt(r, "min", 0), GsonHelper.getAsInt(r, "add", 0),
                GsonHelper.getAsInt(r, "radius", VILLAGE_RADIUS), GsonHelper.getAsString(r, "when", ""));
    }

    /** Is this missing in the giver's village right now? */
    static boolean offerable(QuestDefinition def, net.minecraft.world.entity.Entity villager) {
        Requirement req = requirement(def);
        ServerLevel level = (ServerLevel) villager.level();
        BlockPos pos = villager.blockPosition();
        int villagers = level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(VILLAGE_RADIUS)).size();
        return switch (req.kind()) {
            case "golem" -> villagers >= 3 && level.getEntitiesOfClass(IronGolem.class, new AABB(pos).inflate(VILLAGE_RADIUS)).isEmpty();
            case "poi" -> req.when().equals("homeless") ? current(req, level, pos) < villagers : current(req, level, pos) < Math.max(1, req.min());
            case "blocks" -> req.min() <= 0 || current(req, level, pos) < req.min();
            case "placed" -> true;
            default -> false;
        };
    }

    /** How many there need to be in total for the quest to count as done. */
    static int target(QuestDefinition def, net.minecraft.world.entity.Entity villager) {
        Requirement req = requirement(def);
        ServerLevel level = (ServerLevel) villager.level();
        if (req.kind().equals("golem")) return 1;
        if (req.kind().equals("placed")) return Security.rollCount(def, level.random);
        int now = current(req, level, villager.blockPosition());
        if (req.when().equals("homeless")) {
            int villagers = level.getEntitiesOfClass(Villager.class, new AABB(villager.blockPosition()).inflate(VILLAGE_RADIUS)).size();
            return Math.max(now + Math.max(1, req.add()), villagers);
        }
        return req.min() > 0 ? req.min() : now + Math.max(1, req.add());
    }

    static int current(Requirement req, ServerLevel level, BlockPos pos) {
        return switch (req.kind()) {
            case "golem" -> level.getEntitiesOfClass(IronGolem.class, new AABB(pos).inflate(req.radius()), IronGolem::isPlayerCreated).size();
            case "poi" -> {
                ResourceKey<PoiType> key = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, req.poi());
                yield (int) level.getPoiManager().getCountInRange(h -> h.is(key), pos, req.radius(), PoiManager.Occupancy.ANY);
            }
            case "blocks" -> countBlocks(req, level, pos);
            default -> 0;
        };
    }

    private static int countBlocks(Requirement req, ServerLevel level, BlockPos center) {
        int r = Math.min(req.radius(), MAX_BLOCK_RADIUS);
        TagKey<Block> tag = req.tag() == null ? null : TagKey.create(Registries.BLOCK, req.tag());
        Block block = req.block() == null ? null : BuiltInRegistries.BLOCK.get(req.block());
        int count = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r / 2, -r), center.offset(r, r / 2, r))) {
            if (!level.isLoaded(p)) continue;
            var state = level.getBlockState(p);
            if ((tag != null && state.is(tag)) || (block != null && state.is(block))) count++;
        }
        return count;
    }

    /** Built yet? If so the giver thanks and rewards you. @return true if a build quest was completed. */
    public static boolean tryComplete(ServerPlayer player, Villager villager) {
        PlayerQuests quests = player.getData(QuestRegistries.PLAYER_QUESTS);
        for (ActiveQuest quest : List.copyOf(quests.fromGiver(villager.getUUID()))) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            if (def == null || !def.isBuild() || !quest.isActive()) continue;
            if (have(player.serverLevel(), quest, def) < quest.count) continue;

            QuestDefinition.Rewards r = def.rewards();
            int emeralds = Mth.nextInt(player.getRandom(), r.emeraldsMin(), Math.max(r.emeraldsMin(), r.emeraldsMax()));
            if (emeralds > 0) QuestManager.give(player, new ItemStack(Items.EMERALD, emeralds));
            if (r.xp() > 0) player.giveExperiencePoints(r.xp());
            Reputation.addGoodwill(player, villager, r.goodwillGiver(), r.goodwillVillage());
            if (r.repReturned() > 0) Reputation.adjust(player, villager, r.repReturned(), true);
            QuestText.say(player, villager.getName(), Component.translatable(def.variant(def.text().done(), villager.getUUID()), villager.getName()));
            QuestManager.lookAt(villager, player, SoundEvents.VILLAGER_CELEBRATE);
            quests.quests.remove(quest);
            QuestManager.removeNotesFor(player, quest);
            return true;
        }
        return false;
    }

    /** How much of a build quest is done: counted placements, or what's in the village now. */
    static int have(ServerLevel level, ActiveQuest quest, QuestDefinition def) {
        return Security.isPlacement(def) ? quest.progress : current(requirement(def), level, quest.giverPos);
    }

    /** "3/5 built", for the journal. Reads the village only if it's loaded. */
    static Component status(ServerPlayer player, ActiveQuest quest, QuestDefinition def) {
        if (Security.isPlacement(def)) return Security.status(quest);
        ServerLevel level = player.server.getLevel(quest.dimension);
        if (level == null || !level.isLoaded(quest.giverPos)) {
            return Component.translatable("villagequests.journal.status.build_far");
        }
        int have = Math.min(current(requirement(def), level, quest.giverPos), quest.count);
        return Component.translatable("villagequests.journal.status.build", have, quest.count);
    }
}

package dev.villagechatter.reputation;

import dev.villagechatter.quests.QuestsModule;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

/**
 * Slow, residual village reputation for helping a village thrive. When villagers have a baby, players nearby who
 * are already on decent terms with the village (they've helped it before) get a little village-wide goodwill:
 * beds, food and safety are what make villages grow.
 */
public final class VillageLife {
    private VillageLife() {}

    private static final double NEARBY = 48.0;
    private static final double BIRTH_GOODWILL = 0.25;

    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!QuestsModule.enabled() || !(event.getChild() instanceof Villager baby) || !(baby.level() instanceof ServerLevel level)) return;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(baby) > NEARBY * NEARBY) continue;
            Villager parent = event.getParentA() instanceof Villager p ? p : null;
            if (parent != null && Standing.score(player, parent) < 0) continue;
            Reputation.addVillageGoodwill(player, baby, BIRTH_GOODWILL);
        }
    }
}

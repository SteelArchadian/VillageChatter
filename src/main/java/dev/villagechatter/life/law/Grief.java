package dev.villagechatter.life.law;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.LivingVillage;
import dev.villagechatter.quests.quest.Reputation;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Villages mourn their dead. For a week (7 Minecraft days) after a villager, kid, guard or golem dies, the others
 * now and then say they miss them. Then the record is deleted and everyone moves on. A known killer is forgiven a
 * little at that point.
 */
public final class Grief extends SavedData {
    private static final long MOURNING = 7 * 24000L;
    private static final double MOVED_ON = 4;

    private record Loss(GlobalPos village, String name, String kind, long time, UUID killer) {}

    private final List<Loss> losses = new ArrayList<>();

    private static Grief get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Grief::new, Grief::load, null),
                "villagechatter_grief");
    }

    /** @param killer only if someone saw who did it */
    static void died(LivingEntity victim, ServerPlayer killer) {
        if (!Law.enabled() || !(victim.level() instanceof ServerLevel level)) return;
        Witnesses.village(level, victim.blockPosition()).ifPresent(village -> {
            String kind = victim instanceof Villager v ? (v.isBaby() ? "kid" : "villager")
                    : victim instanceof IronGolem ? "golem" : GuardTrust.isGuard(victim) ? "guard" : null;
            if (kind == null) return;
            String name = victim.hasCustomName() ? victim.getCustomName().getString() : "";
            Grief grief = get(level.getServer());
            grief.losses.add(new Loss(village, name, kind, level.getGameTime(), killer == null ? null : killer.getUUID()));
            grief.setDirty();
        });
    }

    /** Every few minutes: someone near a player in a mourning village says a word for the dead; old losses fade. */
    public static void tick(MinecraftServer server, int now) {
        if (now % 1200 != 0 || !Law.enabled()) return;
        Grief grief = get(server);
        if (grief.losses.isEmpty()) return;
        long time = server.overworld().getGameTime();
        for (Loss loss : List.copyOf(grief.losses)) {
            if (time - loss.time() > MOURNING) {
                grief.losses.remove(loss);
                grief.setDirty();
                movedOn(server, loss);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getRandom().nextInt(4) != 0) continue;
            ServerLevel level = player.serverLevel();
            List<Loss> here = grief.losses.stream().filter(l -> l.village().dimension() == level.dimension()
                    && l.village().pos().closerThan(player.blockPosition(), 64)).toList();
            if (here.isEmpty()) continue;
            Loss loss = here.get(player.getRandom().nextInt(here.size()));
            for (Villager v : LivingVillage.villagersNear(level, player, 10, true)) {
                if (!LivingVillage.idle(v) || !v.hasLineOfSight(player)) continue;
                String key = "villagechatter.life.grief." + loss.kind() + (loss.name().isEmpty() ? "" : ".named")
                        + "." + player.getRandom().nextInt(3);
                LivingVillage.babble(v, 4, 0, -0.15F, 0.7F); // low and sad
                LivingVillage.say(player, v, Component.translatable(key, loss.name()));
                break;
            }
        }
    }

    private static void movedOn(MinecraftServer server, Loss loss) {
        if (loss.killer() == null) return;
        ServerPlayer killer = server.getPlayerList().getPlayer(loss.killer());
        ServerLevel level = server.getLevel(loss.village().dimension());
        if (killer == null || level == null) return;
        level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(loss.village().pos()).inflate(8))
                .stream().findFirst().ifPresent(v -> Reputation.addVillageGoodwill(killer, v, MOVED_ON));
    }

    private static Grief load(CompoundTag tag, HolderLookup.Provider registries) {
        Grief grief = new Grief();
        for (Tag t : tag.getList("losses", Tag.TAG_COMPOUND)) {
            CompoundTag l = (CompoundTag) t;
            GlobalPos.CODEC.parse(NbtOps.INSTANCE, l.get("village")).result().ifPresent(village -> grief.losses.add(
                    new Loss(village, l.getString("name"), l.getString("kind"), l.getLong("time"),
                            l.hasUUID("killer") ? l.getUUID("killer") : null)));
        }
        return grief;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Loss loss : losses) {
            CompoundTag l = new CompoundTag();
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, loss.village()).result().ifPresent(e -> l.put("village", e));
            l.putString("name", loss.name());
            l.putString("kind", loss.kind());
            l.putLong("time", loss.time());
            if (loss.killer() != null) l.putUUID("killer", loss.killer());
            list.add(l);
        }
        tag.put("losses", list);
        return tag;
    }
}

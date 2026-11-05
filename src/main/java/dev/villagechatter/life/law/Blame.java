package dev.villagechatter.life.law;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;

/**
 * Who is to blame for a hit or a kill: only a player who was the direct cause. That's their own melee hit, a
 * projectile they shot, or their tamed pet's bite. A zombie, a creeper, a fall or a lava trap blames nobody, and so
 * does an explosion (even their own TNT: too easy to set off by accident).
 */
public final class Blame {
    private Blame() {}

    public static ServerPlayer culprit(DamageSource source) {
        if (source == null || source.is(DamageTypeTags.IS_EXPLOSION)) return null;
        Entity cause = source.getEntity(); // a projectile's owner, or the attacker itself
        if (cause instanceof ServerPlayer player) return player.isSpectator() ? null : player;
        if (cause instanceof OwnableEntity pet && pet.getOwner() instanceof ServerPlayer owner) return owner;
        return null;
    }
}

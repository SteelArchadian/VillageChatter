package dev.villagechatter.life;

import java.util.List;

import com.mojang.serialization.Codec;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.api.Factions;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Piglins out of the Nether (Factions v2, item 1).
 * <ul>
 *   <li>A piglin holds out for piglinZombifyTicks (a Minecraft day) away from the Nether before it zombifies, not
 *   vanilla's 15 seconds. Standing on Nether ground (tag villagechatter:piglin_safe) anywhere winds the timer back to
 *   a full day, so a netherrack floor keeps a piglin friend safe, and with a quarter of its time left a piglin walks
 *   to any within 16 blocks. Vanilla does the zombifying itself once the day is up: until then the piglin
 *   is marked immune, but only by us, so piglins a datapack or mod made immune stay that way.</li>
 *   <li>A zombified piglin can be cured like a zombie villager: weakness, then a golden apple, then a few minutes'
 *   wait. The piglins remember who did it.</li>
 * </ul>
 */
public final class PiglinNature {
    private PiglinNature() {}

    /** How often (ticks) piglin timers move. */
    private static final int STEP = 20;
    static final int CURE_MIN = 3600, CURE_MAX = 6000;
    static final double CURE_STANDING = 5;

    /** Marks a piglin something else made immune to zombifying. */
    static final int FOREIGN = 2;

    /** On a piglin: {ticks out of the Nether, 1 if we hold it immune, 0 if not, FOREIGN}. On a zombified piglin: {cure ticks left, 0}. */
    public static final Codec<List<Integer>> CODEC = Codec.INT.listOf();

    public static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity().tickCount % STEP != 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof AbstractPiglin piglin) tickPiglin(piglin, level);
        else if (event.getEntity() instanceof ZombifiedPiglin zombie && zombie.hasData(ChatterAttachments.PIGLIN_NATURE)) tickCure(zombie, level);
    }

    static void tickPiglin(AbstractPiglin piglin, ServerLevel level) {
        if (!piglin.hasData(ChatterAttachments.PIGLIN_NATURE)) {
            // first sight: a piglin some datapack or mod already made immune is not ours to manage
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            piglin.addAdditionalSaveData(tag);
            piglin.setData(ChatterAttachments.PIGLIN_NATURE, List.of(0, tag.getBoolean("IsImmuneToZombification") ? FOREIGN : 0));
        }
        List<Integer> data = piglin.getData(ChatterAttachments.PIGLIN_NATURE);
        if (data.get(1) == FOREIGN) return;
        int ticks = data.get(0);
        if (onNetherrack(piglin)) {
            ticks = 0;
            piglin.getPersistentData().remove(SEEK_KEY);
        }
        else if (!level.dimensionType().piglinSafe()) ticks += STEP;
        int max = ChatterConfig.PIGLIN_ZOMBIFY_TICKS.get();
        if (ticks > 0 && max - ticks <= max * ChatterConfig.PIGLIN_SEEK_FRACTION.get()) seekGround(piglin, level);
        boolean hold = ticks < max;
        piglin.setImmuneToZombification(hold);
        piglin.setData(ChatterAttachments.PIGLIN_NATURE, List.of(ticks, hold ? 1 : 0));
    }

    /** Time running low: walk to the nearest Nether ground in range (searched every few seconds, then held as the walk target). */
    static void seekGround(AbstractPiglin piglin, ServerLevel level) {
        if (piglin.isNoAi() || piglin.getTarget() != null) return;
        var brain = piglin.getBrain();
        java.util.Optional<net.minecraft.world.entity.ai.memory.WalkTarget> current = brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        BlockPos goal = piglin.getPersistentData().contains(SEEK_KEY) ? BlockPos.of(piglin.getPersistentData().getLong(SEEK_KEY)) : null;
        if (goal == null || !level.getBlockState(goal).is(PIGLIN_SAFE) || piglin.tickCount % (STEP * 5) == 0) {
            int range = ChatterConfig.PIGLIN_SEEK_RANGE.get();
            goal = BlockPos.findClosestMatch(piglin.blockPosition(), range, 4,
                    p -> level.getBlockState(p).is(PIGLIN_SAFE) && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                            && level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()).orElse(null);
            if (goal == null) {
                piglin.getPersistentData().remove(SEEK_KEY);
                return;
            }
            piglin.getPersistentData().putLong(SEEK_KEY, goal.asLong());
        }
        BlockPos stand = goal.above();
        if (current.isEmpty() || !current.get().getTarget().currentBlockPosition().equals(stand)) {
            brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(stand, 1.0F, 0));
        }
    }

    private static final String SEEK_KEY = "villagechatter_seek_ground";

    /** Nether ground that resets the clock (Factions v2, item 9d): netherrack, nylium, soul sand and soil, basalt, blackstone, magma. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> PIGLIN_SAFE = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("villagechatter", "piglin_safe"));

    static boolean onNetherrack(AbstractPiglin piglin) {
        BlockPos below = piglin.getOnPos();
        return piglin.level().getBlockState(below).is(PIGLIN_SAFE);
    }

    /** Ticks this piglin has spent out of the Nether since it last stood on netherrack. */
    public static int ticksAway(AbstractPiglin piglin) {
        return piglin.getData(ChatterAttachments.PIGLIN_NATURE).get(0);
    }

    // ------------------------------------------------------------------ curing

    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof ZombifiedPiglin zombie) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack held = event.getItemStack();
        if (!held.is(Items.GOLDEN_APPLE) || !zombie.hasEffect(MobEffects.WEAKNESS) || curing(zombie)) return;
        held.consume(1, player);
        startCure(zombie, player, CURE_MIN + zombie.getRandom().nextInt(CURE_MAX - CURE_MIN + 1));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    public static boolean curing(ZombifiedPiglin zombie) {
        return zombie.hasData(ChatterAttachments.PIGLIN_NATURE) && zombie.getData(ChatterAttachments.PIGLIN_NATURE).get(0) > 0;
    }

    /** Starts the cure, like a zombie villager's: strength while it fights the sickness, then it turns back. */
    public static void startCure(ZombifiedPiglin zombie, ServerPlayer curer, int ticks) {
        zombie.setData(ChatterAttachments.PIGLIN_NATURE, List.of(ticks, 0));
        zombie.setData(ChatterAttachments.PIGLIN_CURER, curer == null ? "" : curer.getStringUUID());
        zombie.removeEffect(MobEffects.WEAKNESS);
        zombie.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, ticks, Math.min(zombie.level().getDifficulty().getId() - 1, 0)));
        zombie.setPersistenceRequired();
        zombie.level().playSound(null, zombie.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1.0F, 0.8F);
    }

    static void tickCure(ZombifiedPiglin zombie, ServerLevel level) {
        int left = zombie.getData(ChatterAttachments.PIGLIN_NATURE).get(0);
        if (left <= 0) return;
        left -= STEP;
        if (zombie.getRandom().nextInt(3) == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, zombie.getX(), zombie.getY() + 1.2, zombie.getZ(), 3, 0.3, 0.4, 0.3, 0);
        }
        if (left > 0) {
            zombie.setData(ChatterAttachments.PIGLIN_NATURE, List.of(left, 0));
            return;
        }
        finishCure(zombie, level);
    }

    static Piglin finishCure(ZombifiedPiglin zombie, ServerLevel level) {
        String curer = zombie.getData(ChatterAttachments.PIGLIN_CURER);
        Piglin piglin = zombie.convertTo(EntityType.PIGLIN, true);
        if (piglin == null) return null;
        piglin.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
        piglin.setPersistenceRequired();
        level.levelEvent(null, 1027, piglin.blockPosition(), 0); // the zombie villager's cure sound
        if (!curer.isEmpty()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(curer));
            if (player != null) FactionBook.adjust(player, Factions.Faction.PIGLIN, CURE_STANDING);
        }
        return piglin;
    }
}

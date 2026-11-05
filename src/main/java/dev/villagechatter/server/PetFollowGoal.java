package dev.villagechatter.server;

import java.util.EnumSet;

import dev.villagechatter.ChatterAttachments;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Replaces vanilla's FollowOwnerGoal so the follow distance can be set by talking to the pet
 * (stay close / normal / loose), and so a pet that was sent home doesn't get pulled back.
 * Distances stay under vanilla's 12-block teleport range.
 */
public class PetFollowGoal extends Goal {
    /** {start following when farther than, stop when closer than} per follow mode. */
    private static final float[][] DISTANCES = {
            {3.5F, 1.5F},   // close
            {10.0F, 2.0F},  // normal (vanilla)
            {11.0F, 6.0F},  // loose: roams and flanks
    };

    private final TamableAnimal pet;
    private final double speed;
    private LivingEntity owner;
    private int timeToRecalcPath;

    public PetFollowGoal(TamableAnimal pet, double speed) {
        this.pet = pet;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private float[] distances() {
        int mode = pet.getData(ChatterAttachments.FOLLOW_MODE);
        return DISTANCES[Math.max(0, Math.min(DISTANCES.length - 1, mode))];
    }

    @Override
    public boolean canUse() {
        LivingEntity o = pet.getOwner();
        if (o == null || pet.unableToMoveToOwner() || PetTalk.isGoingHome(pet)) return false;
        float start = distances()[0];
        if (pet.distanceToSqr(o) < start * start) return false;
        owner = o;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (pet.getNavigation().isDone() || pet.unableToMoveToOwner() || PetTalk.isGoingHome(pet)) return false;
        float stop = distances()[1];
        return pet.distanceToSqr(owner) > stop * stop;
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        pet.getNavigation().stop();
    }

    @Override
    public void tick() {
        boolean teleport = pet.shouldTryTeleportToOwner();
        if (!teleport) pet.getLookControl().setLookAt(owner, 10.0F, pet.getMaxHeadXRot());
        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = adjustedTickDelay(10);
            if (teleport) pet.tryToTeleportToOwner();
            else pet.getNavigation().moveTo(owner, speed);
        }
    }
}

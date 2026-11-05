package dev.villagechatter.life.law;

import net.minecraft.util.RandomSource;

/**
 * Crimes, lightest first (see LAW_AND_ORDER.md section 2). Fines are in emeralds. {@code victimHit} is the reputation
 * the wronged one takes; witnesses take about half of it and the village a quarter.
 */
public enum CrimeKind {
    BED(1, 3, 4, true, false),
    THEFT(2, 6, 4, true, false),
    LEAD_ANIMAL(2, 5, 4, false, false),
    /** Hurting a village's (or a caravan's) animals: a smaller weight than hurting people. */
    ASSAULT_ANIMAL(1, 4, 3, true, false),
    LEAD_GOLEM(4, 8, 8, false, false),
    ASSAULT_VILLAGER(3, 8, 10, false, false),
    /** Threatening to kill a witness (and failing to scare them quiet). */
    THREAT(3, 8, 8, false, false),
    ASSAULT_GUARD(4, 10, 10, false, false),
    ASSAULT_KID(18, 22, 16, false, false),
    KILL_VILLAGER(30, 40, 40, false, true),
    KILL_GUARD(40, 50, 50, false, true),
    KILL_GOLEM(40, 50, 50, false, true),
    KILL_KID(64, 64, 80, false, true);

    public final int fineMin, fineMax;
    public final double victimHit;
    /** Light enough for a "come on, just let it slide" check. */
    public final boolean slideable;
    /** No denying, no bribe: pay or fight. */
    public final boolean murder;

    CrimeKind(int fineMin, int fineMax, double victimHit, boolean slideable, boolean murder) {
        this.fineMin = fineMin;
        this.fineMax = fineMax;
        this.victimHit = victimHit;
        this.slideable = slideable;
        this.murder = murder;
    }

    public int rollFine(RandomSource random) {
        return fineMin + random.nextInt(fineMax - fineMin + 1);
    }

    /** Each extra hit on a kid adds about 5 more. */
    public int repeatFine(RandomSource random) {
        return this == ASSAULT_KID ? 3 + random.nextInt(5) : Math.max(1, rollFine(random) / 3);
    }
}

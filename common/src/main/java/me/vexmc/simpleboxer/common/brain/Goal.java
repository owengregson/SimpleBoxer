package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * One scored candidate behavior in the utility arbiter: Chase, MaintainPocket,
 * CircleStrafe, RetreatToHeal, RodPoke, Blockhit, NavigateAroundObstacle,
 * SeekFood, Reposition, … Each turns the {@link Perception} into a scalar
 * utility (typically a weighted product of {@link Consideration}s) and, when it
 * wins arbitration, into an {@link Intent}. Adding a behavior is adding one
 * implementation to the registry — the modular "routine" the rework is built on.
 */
public interface Goal {

    /** Stable id, used for the incumbent/dwell bookkeeping and config weights. */
    @NotNull String id();

    /** This tick's desirability, ≥ 0. The arbiter picks the highest. */
    double utility(@NotNull Perception perception);

    /** What to do when this goal wins — never called with a zero-utility goal. */
    @NotNull Intent decide(@NotNull Perception perception, @NotNull BrainMemory memory);

    /**
     * Minimum ticks the goal keeps control once it wins, so a borderline score
     * does not flip-flop with a rival every tick (anti-dither hysteresis).
     */
    default int minDwellTicks() {
        return 0;
    }

    /** A small utility bonus while this goal is the incumbent (commitment). */
    default double commitBonus() {
        return 0.0;
    }

    /**
     * When true and winning, this goal HARD-SEIZES control: no other goal may
     * pre-empt it until it stops being exclusive (a survive/heal latch).
     */
    default boolean exclusive(@NotNull Perception perception) {
        return false;
    }
}

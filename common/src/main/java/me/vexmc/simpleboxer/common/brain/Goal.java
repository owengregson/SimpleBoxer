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

    /**
     * When this goal is the winner, whether the click controller should hold its
     * fire — a retreating/healing/eating boxer does not swing. Combat goals leave
     * it false so the CPS clock keeps attacking.
     */
    default boolean suppressesAttack() {
        return false;
    }

    /**
     * Whether a boxer driven by this goal may walk off ledges toward its heading.
     * Pursuit/engage goals return {@code true} — chasing a target off an edge is
     * exactly what a real client does — and get the dynamic fall-damage drop
     * budget (never an unlimited one). Survival goals (flee/heal/retreat) keep
     * the default {@code false} and the conservative
     * {@link ContextSteering#LEDGE_MAX_DROP} depth. The brain turns this flag
     * into the drop budget every motor layer shares.
     */
    default boolean mayLeaveLedges() {
        return false;
    }
}

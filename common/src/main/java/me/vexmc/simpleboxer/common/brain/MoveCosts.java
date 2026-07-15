package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;

/**
 * The tick-scaled cost model the server-side pathfinder edges and heuristic are priced in —
 * a pure port of Baritone's {@code ActionCosts} (algorithm reference only; no code copied),
 * reconciled against {@link ClientPhysics} so a planned edge costs what the integrator the
 * boxer actually runs would spend on it.
 *
 * <p>Every value is a <b>tick count</b>. Horizontal costs are {@code 20 ticks/second} divided
 * by the vanilla block/second travel speed (walk 4.317, sprint 5.612). Vertical costs are the
 * tick-by-tick integral of the vanilla motion arc: the free-fall drag integral for a drop
 * ({@link #distanceToTicks}) and the standing-jump arc for a climb ({@link #jumpTicksToRise}).
 * All doubles, no live types — safe to evaluate on the owning thread and pin in unit tests.</p>
 */
public final class MoveCosts {

    private MoveCosts() {}

    /** Ticks to walk one block: 20 / 4.317 ≈ 4.6333. */
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    /** Ticks to sprint one block: 20 / 5.612 ≈ 3.5638 (sprint is 1.3× walk speed). */
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    /** Sprint / walk cost ratio — reconciles to 1 / {@link ClientPhysics#SPRINT_SPEED_MULTIPLIER}. */
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK / WALK_ONE_BLOCK;
    /** One sprinted diagonal block covers √2 distance. */
    public static final double SPRINT_ONE_BLOCK_DIAGONAL = SPRINT_ONE_BLOCK * Math.sqrt(2.0);

    /** Walking off a ledge only carries you 0.8 of the block before you leave it. */
    public static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    /** The remaining 0.2 of a block, walked back to centre after a landing. */
    public static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;

    /** A sentinel "impossible" cost (an unreachable move), matching Baritone's {@code COST_INF}. */
    public static final double COST_INF = 1_000_000.0;

    /** Largest fall the precomputed table covers; deeper drops fall back to {@link #distanceToTicks}. */
    public static final int MAX_FALL_BLOCKS = 256;

    /** {@code FALL_N_BLOCKS[n]} = ticks to free-fall {@code n} blocks (monotone increasing, [0] == 0). */
    public static final double[] FALL_N_BLOCKS = generateFallTable();

    /** Ticks a standing sprint-jump spends climbing one block — reconciled to the {@link ClientPhysics} arc. */
    public static final double JUMP_ONE_BLOCK = jumpTicksToRise(1.0);

    /**
     * Distance fallen on tick {@code ticks} of a free fall: {@code (0.98^t − 1)·−3.92}. This is the
     * closed form of vanilla vertical motion (0.08 gravity, 0.98 drag, −3.92 terminal). {@code t = 0}
     * yields 0 — the tick you leave the ground you have not fallen yet — exactly Baritone's convention.
     */
    static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1.0) * -3.92;
    }

    /**
     * Ticks to free-fall {@code distance} blocks, integrating {@link #velocity} tick by tick until the
     * remaining distance is covered (the last tick is credited fractionally). Zero for a non-positive
     * distance. Guarded against runaway input; a boxer never free-falls thousands of blocks.
     */
    public static double distanceToTicks(double distance) {
        if (distance <= 0.0) {
            return 0.0;
        }
        double remaining = distance;
        int ticks = 0;
        while (true) {
            double fell = velocity(ticks);
            if (remaining <= fell) {
                return ticks + remaining / fell;
            }
            remaining -= fell;
            ticks++;
            if (ticks > 4096) {
                return ticks; // safety valve — unreachable in a real arena
            }
        }
    }

    /**
     * Ticks a standing jump (initial {@code vy = }{@link ClientPhysics#DEFAULT_JUMP_STRENGTH}) spends
     * rising {@code height} blocks, integrated exactly as {@link ClientPhysics} does its vertical step
     * (apply velocity to position, then {@code vy = (vy − gravity)·drag}). Returns {@link #COST_INF}
     * when {@code height} is above the jump apex (≈ 1.2522 blocks) — a plain jump cannot reach it.
     */
    public static double jumpTicksToRise(double height) {
        if (height <= 0.0) {
            return 0.0;
        }
        double y = 0.0;
        double vy = ClientPhysics.DEFAULT_JUMP_STRENGTH;
        int ticks = 0;
        while (vy > 0.0) {
            double next = y + vy;
            if (next >= height) {
                return ticks + (height - y) / vy;
            }
            y = next;
            vy = (vy - ClientPhysics.GRAVITY) * ClientPhysics.VERTICAL_DRAG;
            ticks++;
        }
        return COST_INF; // apex fell short of the target height
    }

    /**
     * Ticks to fall {@code blocks} blocks, reading the precomputed {@link #FALL_N_BLOCKS} table with a
     * linear interpolation for the fractional remainder (deeper than the table falls back to the
     * closed integral). The horizontal cost of a fall move is layered on separately by the planner.
     */
    public static double fallTicks(double blocks) {
        if (blocks <= 0.0) {
            return 0.0;
        }
        int i = (int) Math.floor(blocks);
        if (i >= MAX_FALL_BLOCKS) {
            return distanceToTicks(blocks);
        }
        double frac = blocks - i;
        return FALL_N_BLOCKS[i] + frac * (FALL_N_BLOCKS[i + 1] - FALL_N_BLOCKS[i]);
    }

    private static double[] generateFallTable() {
        double[] table = new double[MAX_FALL_BLOCKS + 1];
        for (int i = 0; i <= MAX_FALL_BLOCKS; i++) {
            table[i] = distanceToTicks(i);
        }
        return table;
    }
}

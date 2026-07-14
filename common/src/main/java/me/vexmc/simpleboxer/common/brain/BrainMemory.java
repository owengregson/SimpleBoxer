package me.vexmc.simpleboxer.common.brain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-boxer mutable brain scratchpad — touched ONLY on the owning thread,
 * so plain fields are safe. Its single {@link Random} is seeded from the boxer's
 * uuid so identical boxers make identical decisions across the test matrix
 * (no {@code System.nanoTime}, no {@code Math.random} anywhere in the brain).
 */
public final class BrainMemory {

    /** The one source of randomness for every brain decision. */
    public final Random rng;

    /* Arbiter hysteresis. */
    public @Nullable String incumbentGoal;
    public int dwellTicks;

    /* Strafe state (moved out of BoxerImpl). */
    public int strafeSign = 1;
    public int strafeFlipIn;

    /* W-tap / s-tap sprint-reset state machine. */
    public int wtapCountdown = -1;
    public int wtapReleaseLeft;

    /* Follow-up-over-terrain gate. */
    public int climbTicks;

    /* Anti-stuck: a rolling record of horizontal progress vs intent. */
    private final double[] progress = new double[8];
    private int progressCursor;

    /* A cached local path the navigate goal follows, and its plan bookkeeping. */
    public @Nullable List<Vec3d> path;
    public int pathCursor;
    public long lastPlanTick = Long.MIN_VALUE;
    public long lastGoalCell = Long.MIN_VALUE;

    /* Per-routine scratch, lazily allocated by routine id. */
    private final Map<String, int[]> intScratch = new HashMap<>();
    private final Map<String, double[]> doubleScratch = new HashMap<>();

    public BrainMemory(long seed) {
        this.rng = new Random(seed);
    }

    /** A stable int[] scratch array for a routine, created zeroed on first use. */
    public int[] ints(@NotNull String routineId, int size) {
        return intScratch.computeIfAbsent(routineId, k -> new int[size]);
    }

    /** A stable double[] scratch array for a routine, created zeroed on first use. */
    public double[] doubles(@NotNull String routineId, int size) {
        return doubleScratch.computeIfAbsent(routineId, k -> new double[size]);
    }

    /** Record this tick's realized horizontal progress for stall detection. */
    public void recordProgress(double horizontalDistanceMoved) {
        progress[progressCursor] = horizontalDistanceMoved;
        progressCursor = (progressCursor + 1) % progress.length;
    }

    /** Mean horizontal progress over the recent window (blocks/tick). */
    public double recentProgress() {
        double sum = 0.0;
        for (double value : progress) {
            sum += value;
        }
        return sum / progress.length;
    }

    /** Forget the cached path (target changed, route failed, or reached). */
    public void clearPath() {
        this.path = null;
        this.pathCursor = 0;
    }
}

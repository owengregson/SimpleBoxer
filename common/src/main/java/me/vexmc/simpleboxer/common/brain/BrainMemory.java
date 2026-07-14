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

    /* Anti-stuck: a rolling record of recent horizontal POSITIONS, so the net
     * displacement over the window distinguishes a genuinely-pinned boxer (which
     * jitters in place — high speed, ~zero net travel) from one creeping along a
     * wall (low speed but real net travel). */
    private static final int PROGRESS_WINDOW = 8;
    private final double[] posX = new double[PROGRESS_WINDOW];
    private final double[] posZ = new double[PROGRESS_WINDOW];
    private int posCursor;
    private boolean posSeeded;

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

    /** Record the boxer's horizontal position this tick for stall detection. */
    public void recordPosition(double x, double z) {
        if (!posSeeded) {
            for (int i = 0; i < PROGRESS_WINDOW; i++) {
                posX[i] = x;
                posZ[i] = z;
            }
            posSeeded = true;
        }
        posX[posCursor] = x;
        posZ[posCursor] = z;
        posCursor = (posCursor + 1) % PROGRESS_WINDOW;
    }

    /**
     * Net horizontal displacement across the window (blocks) — the distance from
     * the oldest recorded position to the newest. Near zero for a boxer stuck
     * jittering against a wall even while its per-tick speed is high.
     */
    public double netProgress() {
        int newest = (posCursor - 1 + PROGRESS_WINDOW) % PROGRESS_WINDOW;
        int oldest = posCursor; // the slot about to be overwritten holds the oldest sample
        double dx = posX[newest] - posX[oldest];
        double dz = posZ[newest] - posZ[oldest];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Forget the cached path (target changed, route failed, or reached). */
    public void clearPath() {
        this.path = null;
        this.pathCursor = 0;
    }
}

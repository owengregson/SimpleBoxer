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

    /* Monotonic decision-tick counter — the phase the motor duty-cycles a softened
     * forward key against (easing off near walls/ledges without a fractional impulse). */
    public int motorTick;

    /* Strafe state (moved out of BoxerImpl). */
    public int strafeSign = 1;
    public int strafeFlipIn;

    /* W-tap / s-tap sprint-reset state machine. */
    public int wtapCountdown = -1;
    public int wtapReleaseLeft;
    /* Set for the single tick the forward key re-presses after a w-tap release
     * window (sprint about to re-arm) — the seam adaptive strafing syncs a juke
     * to, so the direction change and the fresh sprint knock land together. */
    public boolean wtapRepressed;

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

    /* A cached local path the navigate goal follows, and its plan bookkeeping.
     * lastPlanTick is in decision ticks (motorTick units) and throttles the
     * heavyweight elevation planner; lastGoalCell is the target's block cell at
     * plan time. */
    public @Nullable List<Vec3d> path;
    public int pathCursor;
    public long lastPlanTick = Long.MIN_VALUE;
    public long lastGoalCell = Long.MIN_VALUE;

    /* Elevation latch: while set, the follower holds a committed climbing route
     * across target cell changes (replan-while-following swaps it for a better
     * plan when one exists) so orbit/pocket gates cannot dissolve a climb
     * mid-stairs. climbGoalY is the target level the latch releases at. */
    public boolean climbLatch;
    public double climbGoalY;

    /* Ticks spent on the CURRENT waypoint without advancing the cursor — the
     * follower's geometric-invalidation clock (a waypoint the boxer cannot reach
     * within the stall bound means the route no longer matches the world or the
     * boxer was knocked hopelessly off it). Zeroed on adopt and on every cursor
     * advance. */
    public int waypointTicks;

    /* Scheduled-takeoff cue from the route follower: the step-face distance
     * (blocks of box travel) and rise of the next ASCEND waypoint, NaN/0 when
     * none. Reset every decision tick by the heading resolver; consumed by
     * ProactiveJump when its geometric face probe misses. */
    public double routeStepFace = Double.NaN;
    public double routeStepRise;

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

    /**
     * Respawn reset: a new life starts with no routine mid-episode, no committed
     * route, and no combo state — death ended them all. Only the rng (identity
     * determinism) and the motor duty phase survive. The position history
     * re-seeds at the respawn point so anti-stuck cannot read the
     * death-to-spawn relocation as a burst of travel.
     */
    public void onRespawn() {
        incumbentGoal = null;
        dwellTicks = 0;
        strafeSign = 1;
        strafeFlipIn = 0;
        wtapCountdown = -1;
        wtapReleaseLeft = 0;
        wtapRepressed = false;
        climbTicks = 0;
        clearPath();
        lastPlanTick = Long.MIN_VALUE;
        lastGoalCell = Long.MIN_VALUE;
        climbLatch = false;
        climbGoalY = 0.0;
        waypointTicks = 0;
        routeStepFace = Double.NaN;
        routeStepRise = 0.0;
        posSeeded = false;
        intScratch.clear();
        doubleScratch.clear();
    }
}

package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * Feature 4 — decides which way (and how often) the boxer should sidestep, from
 * how the opponent is aiming at it. Three flavors:
 *
 * <ul>
 *   <li>{@link StrafeMode#NONE} — no sidestep; the caller strafes straight-on
 *       (or applies its own s-tap), so we just echo the current sign.</li>
 *   <li>{@link StrafeMode#WEAVE} — a jittery short-cadence left/right weave to
 *       spoil aim, flipping every {@value #WEAVE_MIN}-{@value #WEAVE_MAX} ticks.</li>
 *   <li>{@link StrafeMode#ORBIT} — a slower circle-strafe. Non-adaptive it flips
 *       on a long cadence (or when a wall interrupts); adaptive it reads the
 *       opponent's aim-tracking rate and jukes the other way the moment the
 *       opponent locks onto the current direction.</li>
 * </ul>
 *
 * <p>The interesting signal is {@link Perception.TargetState#oppTrackRateDegPerTick()}:
 * a large positive value means the opponent is sweeping their crosshair fast to
 * <em>keep up</em> with our current strafe — they have the read, so we flip to
 * break it. A small or negative rate means they are lagging / mis-tracking, so
 * we hold the direction to keep exploiting the miss. A seeded min-dwell counter
 * stops the flip from firing every tick (which would just jitter in place and
 * read as a machine), so the juke lands as a single decisive change of pace.
 *
 * <p>Pure and deterministic: the only randomness comes from {@link BrainMemory#rng}.
 * The chosen sign lives in {@link BrainMemory#strafeSign} so it persists across
 * ticks and survives a mode switch; {@link BrainMemory#strafeFlipIn} drives the
 * fixed WEAVE/ORBIT cadences and the adaptive min-dwell lives in this module's
 * own {@link BrainMemory#ints(String, int)} scratch slot.
 */
public final class AdaptiveStrafe {

    /** Scratch id for the adaptive min-dwell counter (index 0 = ticks until a flip is allowed again). */
    private static final String SCRATCH_ID = "adaptiveStrafe";

    /** WEAVE flips on a short, jittery cadence so the boxer never sits on one bearing. */
    static final int WEAVE_MIN = 7;
    static final int WEAVE_MAX = 14;

    /** ORBIT (non-adaptive) flips on a much slower cadence — a genuine circle, not a shimmy. */
    static final int ORBIT_MIN = 40;
    static final int ORBIT_MAX = 65;

    /**
     * Aim-sweep rate (deg/tick) above which we treat the opponent as tracking us
     * tightly. Kept a little above zero so ordinary aim noise doesn't read as a
     * lock and trigger a needless flip.
     */
    static final double TRACK_THRESHOLD_DEG_PER_TICK = 3.0;

    /** After an adaptive flip, refuse to flip again for this base window (plus jitter). */
    static final int ADAPTIVE_MIN_DWELL = 5;
    /** Seeded jitter added to the min-dwell so consecutive flips aren't perfectly periodic. */
    static final int ADAPTIVE_DWELL_JITTER = 6;

    /** The sidestep style the caller wants this tick. */
    public enum StrafeMode {
        ORBIT,
        WEAVE,
        NONE
    }

    /**
     * The decision: {@code sign} is the lateral direction the caller feeds to its
     * tangential-heading math ({@code +1}/{@code -1}), {@code mode} echoes what
     * was applied so the caller can log/branch on it.
     */
    public record StrafeDecision(int sign, @NotNull StrafeMode mode) {}

    /**
     * Advance the strafe state one tick and return the direction to sidestep.
     *
     * @param p        the current perception snapshot
     * @param mode     the sidestep style requested by the calling goal
     * @param adaptive whether ORBIT should read the opponent's tracking (ignored for WEAVE/NONE)
     * @param mem      the owning boxer's mutable scratchpad (mutated in place)
     * @return the sign to strafe and the mode that produced it
     */
    public @NotNull StrafeDecision next(@NotNull Perception p, @NotNull StrafeMode mode,
                                        boolean adaptive, @NotNull BrainMemory mem) {
        return switch (mode) {
            // No sidestep: leave all cadence state untouched and echo the current bearing.
            case NONE -> new StrafeDecision(mem.strafeSign, StrafeMode.NONE);
            case WEAVE -> new StrafeDecision(fixedCadence(mem, WEAVE_MIN, WEAVE_MAX), StrafeMode.WEAVE);
            case ORBIT -> new StrafeDecision(orbit(p, adaptive, mem), StrafeMode.ORBIT);
        };
    }

    /** ORBIT: adaptive juke off the opponent's aim when possible, else a slow timed circle. */
    private int orbit(@NotNull Perception p, boolean adaptive, @NotNull BrainMemory mem) {
        if (adaptive && p.hasTarget()) {
            return adaptiveOrbit(p, mem);
        }
        // Non-adaptive: flip on the slow cadence, or immediately if a wall stalled the circle.
        if (p.self().horizontalCollision()) {
            flip(mem);
            mem.strafeFlipIn = rollCadence(mem, ORBIT_MIN, ORBIT_MAX);
            return mem.strafeSign;
        }
        return fixedCadence(mem, ORBIT_MIN, ORBIT_MAX);
    }

    /**
     * Adaptive ORBIT: flip to break the track when the opponent is sweeping onto
     * us, but only after the seeded min-dwell has expired so we don't chatter.
     */
    private int adaptiveOrbit(@NotNull Perception p, @NotNull BrainMemory mem) {
        int[] scratch = mem.ints(SCRATCH_ID, 1);
        if (scratch[0] > 0) {
            scratch[0]--;
        }

        boolean tracked = p.target().oppTrackRateDegPerTick() > TRACK_THRESHOLD_DEG_PER_TICK;
        // A wall is a hard reason to reverse IMMEDIATELY — it must bypass the
        // min-dwell (as the non-adaptive path does), or the boxer keeps strafing
        // into the wall for the whole dwell window. A tight track only reverses
        // once the dwell has expired, so the juke doesn't chatter every tick.
        boolean walled = p.self().horizontalCollision();
        if (walled) {
            flip(mem);
            scratch[0] = ADAPTIVE_MIN_DWELL + mem.rng.nextInt(ADAPTIVE_DWELL_JITTER);
        } else if (tracked && scratch[0] <= 0) {
            flip(mem);
            scratch[0] = ADAPTIVE_MIN_DWELL + mem.rng.nextInt(ADAPTIVE_DWELL_JITTER);
        }
        // Low / negative rate (or still within dwell): hold the current sign to exploit the miss.
        return mem.strafeSign;
    }

    /**
     * A plain timed flip: count {@link BrainMemory#strafeFlipIn} down and reverse
     * when it hits zero, re-rolling the next interval with seeded jitter.
     */
    private int fixedCadence(@NotNull BrainMemory mem, int min, int max) {
        if (mem.strafeFlipIn <= 0) {
            flip(mem);
            mem.strafeFlipIn = rollCadence(mem, min, max);
        } else {
            mem.strafeFlipIn--;
        }
        return mem.strafeSign;
    }

    /** Reverse the persisted strafe direction, guarding against a stray zero. */
    private void flip(@NotNull BrainMemory mem) {
        mem.strafeSign = (mem.strafeSign >= 0) ? -1 : 1;
    }

    /** A seeded cadence length in {@code [min, max]} ticks. */
    private int rollCadence(@NotNull BrainMemory mem, int min, int max) {
        return min + mem.rng.nextInt(max - min + 1);
    }
}

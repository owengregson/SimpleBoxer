package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Vec3d;
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
 *       on a long cadence (or when a wall interrupts); adaptive it actively
 *       <em>chooses</em> a side each tick from how the opponent aims and moves,
 *       juking to the side their crosshair is not covering.</li>
 * </ul>
 *
 * <p>Adaptive ORBIT reads three signals, in priority order. The SIGNED aim-sweep
 * {@link Perception.TargetState#signedTrackRateDegPerTick()} tells it <em>which</em>
 * way the opponent's crosshair is chasing — a large positive sweep means their aim
 * is racing toward higher bearing, so the open side (the one they are lagging away
 * from) is the {@code +} one, and it strafes there to break the track. Failing a
 * tight track, {@link Perception.TargetState#velocity()} lets it juke opposite the
 * opponent's tangential motion to open an angle their momentum can't follow. Failing
 * any read, a slow seeded cadence keeps a calm-aim circle changing pace so it never
 * sits perfectly predictable. A wall reverses immediately; a w-tap cycle defers the
 * juke to the sprint re-press.
 *
 * <p>A seeded min-dwell stops a discretionary change firing on adjacent ticks
 * (which would jitter in place and read as a machine), so each juke lands as a
 * single decisive change of pace.
 *
 * <p>Pure and deterministic: the only randomness comes from {@link BrainMemory#rng}.
 * The chosen sign lives in {@link BrainMemory#strafeSign} so it persists across
 * ticks and survives a mode switch; {@link BrainMemory#strafeFlipIn} drives the
 * fixed WEAVE/ORBIT cadences and the adaptive min-dwell lives in this module's
 * own {@link BrainMemory#ints(String, int)} scratch slot.
 */
public final class AdaptiveStrafe {

    /**
     * Scratch id for the adaptive strafe counters:
     * <ul>
     *   <li>index 0 — min-dwell: ticks until a discretionary side change is allowed again.</li>
     *   <li>index 1 — a side change stashed while a w-tap cycle is in flight, applied
     *       on the sprint re-press ({@code 0} = nothing pending).</li>
     *   <li>index 2 — the slow calm-aim cadence: ticks until the next seeded juke when
     *       there is no aim/velocity read to exploit.</li>
     * </ul>
     */
    private static final String SCRATCH_ID = "adaptiveStrafe";
    private static final int DWELL = 0;
    private static final int PENDING = 1;
    private static final int CALM = 2;

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

    /**
     * Minimum tangential opponent speed (blocks/tick) that reads as a real slide
     * worth juking against — below this the opponent is essentially stationary or
     * closing straight-on, so their motion carries no side to open.
     */
    static final double VELOCITY_JUKE_THRESHOLD = 0.05;

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
     * Adaptive ORBIT: actively CHOOSE a side each tick rather than merely holding
     * or blind-flipping a fixed one. In priority order the boxer:
     *
     * <ol>
     *   <li>reverses off a wall immediately (bypasses the min-dwell);</li>
     *   <li>strafes to the side the opponent's crosshair is <em>not</em> covering,
     *       read from the SIGNED aim-sweep — a positive sweep means their aim is
     *       chasing toward higher bearing, so the open side is the {@code +} one
     *       (and vice-versa);</li>
     *   <li>failing a tight track, jukes opposite the opponent's tangential
     *       velocity to open the angle their momentum can't follow;</li>
     *   <li>failing any read, keeps a slow seeded cadence so a calm-aim circle
     *       still changes pace and never sits perfectly predictable.</li>
     * </ol>
     *
     * <p>A discretionary change is gated by the seeded min-dwell (no chatter) and,
     * while a w-tap sprint-reset cycle is in flight, is deferred and landed on the
     * re-press tick ({@link BrainMemory#wtapRepressed}) so the juke and the fresh
     * sprint knock arrive together.
     */
    private int adaptiveOrbit(@NotNull Perception p, @NotNull BrainMemory mem) {
        int[] scratch = mem.ints(SCRATCH_ID, 3);
        if (scratch[DWELL] > 0) {
            scratch[DWELL]--;
        }

        // A wall is a hard reason to reverse IMMEDIATELY — it must bypass the
        // min-dwell (as the non-adaptive path does), or the boxer keeps strafing
        // into the wall for the whole dwell window.
        if (p.self().horizontalCollision()) {
            flip(mem);
            scratch[PENDING] = 0;
            armDwell(scratch, mem);
            mem.wtapRepressed = false;
            return mem.strafeSign;
        }

        // Consume the one-tick re-press seam (whether or not we use it this tick).
        boolean repress = mem.wtapRepressed;
        mem.wtapRepressed = false;

        // A change stashed on a previous tick lands the moment sprint re-presses.
        if (repress && scratch[PENDING] != 0) {
            mem.strafeSign = scratch[PENDING];
            scratch[PENDING] = 0;
            armDwell(scratch, mem);
            return mem.strafeSign;
        }

        int desired = chooseSide(p, mem, scratch);
        if (desired != 0 && desired != mem.strafeSign && scratch[DWELL] <= 0) {
            // Mid w-tap cycle: stash the juke and hold, so it lands with the sprint
            // re-press instead of leaking out during the forward-released window.
            if (wtapCycleActive(mem)) {
                scratch[PENDING] = desired;
                return mem.strafeSign;
            }
            mem.strafeSign = desired;
            armDwell(scratch, mem);
        }
        return mem.strafeSign;
    }

    /**
     * The side the signals want this tick: {@code +1}/{@code -1}, or {@code 0} to
     * hold. Tight-track aim-break outranks the velocity juke, which outranks the
     * slow calm-aim cadence.
     */
    private int chooseSide(@NotNull Perception p, @NotNull BrainMemory mem, int[] scratch) {
        Perception.TargetState t = p.target();

        // 1. Break a tight track: strafe to the side their sweep is lagging away from.
        double signed = t.signedTrackRateDegPerTick();
        if (Math.abs(signed) > TRACK_THRESHOLD_DEG_PER_TICK) {
            return signed > 0 ? 1 : -1;
        }

        // 2. Open the angle against the opponent's tangential motion.
        int velSign = velocityJukeSign(p.self(), t);
        if (velSign != 0) {
            return velSign;
        }

        // 3. Calm aim: keep a slow seeded juke so the circle isn't perfectly periodic.
        return calmCadence(mem, scratch);
    }

    /**
     * The side that strafes OPPOSITE the opponent's tangential velocity (opening
     * the angle their momentum can't follow), or {@code 0} when their motion has
     * no meaningful tangential component. The {@code +1} tangent is 90° CCW of the
     * boxer→target direction (matching {@code EngageGoal.tangent}).
     */
    private int velocityJukeSign(@NotNull Perception.SelfState self,
            @NotNull Perception.TargetState t) {
        Vec3d toTarget = new Vec3d(t.x() - self.x(), 0.0, t.z() - self.z()).horizontalNormalized();
        if (toTarget.lengthSqr() < 1.0E-8) {
            return 0;
        }
        // tangent(+1) = (-dir.z, 0, dir.x) — the left/CCW sidestep direction.
        double along = t.velocity().x() * (-toTarget.z()) + t.velocity().z() * toTarget.x();
        if (Math.abs(along) < VELOCITY_JUKE_THRESHOLD) {
            return 0;
        }
        return along > 0 ? -1 : 1;
    }

    /**
     * The slow calm-aim juke: propose the flipped side when the seeded timer
     * expires. A fresh (zero) counter seeds a full cadence WITHOUT flipping, so
     * entering a calm circle doesn't juke on the first tick — the flip only comes
     * after a genuine {@code ORBIT_MIN..ORBIT_MAX} interval.
     */
    private int calmCadence(@NotNull BrainMemory mem, int[] scratch) {
        if (scratch[CALM] > 1) {
            scratch[CALM]--;
            return 0;
        }
        if (scratch[CALM] == 1) {
            scratch[CALM] = rollCadence(mem, ORBIT_MIN, ORBIT_MAX);
            return mem.strafeSign >= 0 ? -1 : 1;
        }
        scratch[CALM] = rollCadence(mem, ORBIT_MIN, ORBIT_MAX); // seed, no flip yet
        return 0;
    }

    /** A w-tap sprint-reset cycle (delay or release window) is currently running. */
    private boolean wtapCycleActive(@NotNull BrainMemory mem) {
        return mem.wtapCountdown >= 0 || mem.wtapReleaseLeft > 0;
    }

    /** Re-arm the min-dwell with seeded jitter after a discretionary side change. */
    private void armDwell(int[] scratch, @NotNull BrainMemory mem) {
        scratch[DWELL] = ADAPTIVE_MIN_DWELL + mem.rng.nextInt(ADAPTIVE_DWELL_JITTER);
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

package me.vexmc.simpleboxer.common.aim;

/**
 * Spring-damper tuning for the aim controller. {@code stiffness} is the
 * fraction of the angular error converted to velocity each tick;
 * {@code damping} is the fraction of existing velocity that survives a tick
 * (0 = dead beat, →1 = springy); {@code maxVelocity} caps degrees per tick.
 *
 * <p>Underdamped settings (meaningful damping with high stiffness) overshoot
 * naturally when a strafing target flips direction — momentum carries the
 * crosshair past, exactly like a human flick. Overshoot is a physics
 * consequence here, never a bolted-on random.</p>
 */
public record AimParams(double stiffness, double damping, double maxVelocity) {

    /** Aimbot-grade: snaps the full error every tick, no momentum. */
    public static final AimParams LOCKED = new AimParams(1.0, 0.0, 360.0);

    /** A crisp PvP player: fast convergence, slight flick-past on reversals. */
    public static final AimParams SHARP = new AimParams(0.55, 0.30, 60.0);

    /** Comfortable tracking: smooth pursuit, mild lag on direction changes. */
    public static final AimParams SMOOTH = new AimParams(0.30, 0.40, 40.0);

    /** Clearly human-limited: laggy pursuit, visible overshoot, slow flicks. */
    public static final AimParams SLOPPY = new AimParams(0.16, 0.50, 24.0);

    public AimParams {
        if (stiffness < 0.0 || stiffness > 1.0) {
            throw new IllegalArgumentException("stiffness must be in [0,1]: " + stiffness);
        }
        if (damping < 0.0 || damping >= 1.0) {
            throw new IllegalArgumentException("damping must be in [0,1): " + damping);
        }
        if (maxVelocity <= 0.0) {
            throw new IllegalArgumentException("maxVelocity must be positive: " + maxVelocity);
        }
    }
}

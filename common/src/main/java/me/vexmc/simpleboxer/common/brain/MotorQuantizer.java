package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.MoveInput;
import org.jetbrains.annotations.NotNull;

/**
 * The motor stage: turns a world-space {@link MoveHeading} plus the crosshair
 * yaw into the DIGITAL keyboard {@link MoveInput} the integrator consumes.
 *
 * <p>THE guarantee: {@code forward} and {@code strafe} are strictly in
 * {@code {-1.0, 0.0, 1.0}}. A real player holds keys, not analog sticks —
 * {@link me.vexmc.simpleboxer.common.physics.ClientPhysics} silently accepts a
 * fractional impulse (it normalizes only above unit length), but emitting one is
 * a fidelity tell, so we never do.</p>
 *
 * <p>The heading arrives in the world frame; the integrator accelerates in the
 * AIM-YAW frame (movement facing == aim yaw). We project the heading onto that
 * frame and take the sign of each component. Because the projection is exactly
 * the inverse of {@code ClientPhysics.accelerate}'s rotation, a heading that
 * points straight along the crosshair yields {@code forward=1, strafe=0}, and an
 * orbit tangent (perpendicular to the aim) yields a PURE strafe — not the
 * {@code forward=1 + strafe=1} that used to spiral the boxer outward.</p>
 *
 * <p>Pace ({@link MoveHeading#speedScale()} &lt; 1) and "back off" are never
 * expressed as a fractional forward here; a caller duty-cycles the digital
 * forward across ticks (or hands us {@link MoveHeading#STILL}). This stage only
 * quantizes direction.</p>
 */
public final class MotorQuantizer {

    /**
     * A component must claim more than this share of the heading before it earns
     * a key press. 0.35 sits comfortably below the {@code cos(45°) ≈ 0.707} that
     * a diagonal heading puts on BOTH axes (so a 45° heading presses W and A
     * together) yet well above the residue a near-axis-aligned heading leaves on
     * the off axis (so a mostly-forward heading presses W alone).
     */
    public static final double DEADBAND = 0.35;

    /**
     * Duty-cycle window for the softened forward key: when a heading asks for a
     * pace below full ({@code speedScale < 1}), the forward key is held on
     * {@code round(speedScale * DUTY_PERIOD)} of every {@code DUTY_PERIOD} ticks and
     * released the rest, so the AVERAGE forward impulse tracks the requested pace
     * while every individual key stays digital (a real player edging along a rim).
     */
    public static final int DUTY_PERIOD = 4;

    /**
     * Lower the world-space heading onto held keys for the given aim yaw. This
     * overload runs at full pace (no duty-cycle); {@link #toInput(MoveHeading, float,
     * boolean, Intent.JumpHint, boolean, int)} adds the ease-off.
     *
     * @param heading   collision-aware world direction (magnitude ignored; only
     *                  its horizontal direction matters). {@link MoveHeading#nearLedge()}
     *                  forces sneak — the intended edge ease-off
     * @param aimYawDeg the crosshair yaw the boxer is turned to this tick, in
     *                  degrees (vanilla convention: 0 faces +Z)
     * @param sprint    passed through untouched — a caller keeps sprint even on a
     *                  pure-strafe tick; we never fabricate a fractional forward
     *                  to "justify" it
     * @param jump      pressed iff {@link Intent.JumpHint#JUMP}
     * @param sneak     pressed when passed, OR when the heading is near a ledge
     * @return a digital {@link MoveInput} with {@code forward, strafe ∈ {-1,0,1}}
     */
    public @NotNull MoveInput toInput(
            @NotNull MoveHeading heading,
            float aimYawDeg,
            boolean sprint,
            @NotNull Intent.JumpHint jump,
            boolean sneak) {
        boolean wantJump = jump == Intent.JumpHint.JUMP;
        // Near a ledge, ease off by crouching — the intended soft edge behaviour,
        // not a hard avoid (a real player edges along a rim sneaking).
        boolean wantSneak = sneak || heading.nearLedge();

        // No heading -> no movement keys (jump/sneak/sprint still honoured).
        if (heading.isStill()) {
            return new MoveInput(0.0, 0.0, wantJump, sprint, wantSneak);
        }

        // Direction only: strip magnitude so a slow (short) heading still keys
        // fully — pace is a caller's duty-cycle concern, not a fractional key.
        double dx = heading.dirWorld().x();
        double dz = heading.dirWorld().z();
        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;

        double yaw = Math.toRadians(aimYawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        // Inverse of ClientPhysics.accelerate's rotation
        //   worldVx = strafe*cos - forward*sin
        //   worldVz = forward*cos + strafe*sin
        // solved for the keys that reproduce (dx, dz):
        double forward = -dx * sin + dz * cos;
        double strafe = dx * cos + dz * sin;

        return new MoveInput(quantize(forward), quantize(strafe), wantJump, sprint, wantSneak);
    }

    /**
     * As {@link #toInput(MoveHeading, float, boolean, Intent.JumpHint, boolean)} but
     * consuming {@link MoveHeading#speedScale()}: on the "off" ticks of the duty-cycle
     * the forward key is released (strafe/jump/sprint/sneak untouched), converting a
     * sub-unit pace request into a digital ease-off rather than a fractional impulse.
     *
     * @param dutyTick a monotonic per-boxer decision-tick counter (the duty phase)
     */
    public @NotNull MoveInput toInput(
            @NotNull MoveHeading heading,
            float aimYawDeg,
            boolean sprint,
            @NotNull Intent.JumpHint jump,
            boolean sneak,
            int dutyTick) {
        MoveInput base = toInput(heading, aimYawDeg, sprint, jump, sneak);
        if (heading.speedScale() < 1.0 && !forwardHeldThisTick(heading.speedScale(), dutyTick)) {
            return new MoveInput(0.0, base.strafe(), base.jump(), base.sprint(), base.sneak());
        }
        return base;
    }

    /** Whether the duty-cycle holds the forward key on {@code dutyTick} for this pace. */
    private static boolean forwardHeldThisTick(double speedScale, int dutyTick) {
        double s = Math.max(0.0, Math.min(1.0, speedScale));
        int onTicks = (int) Math.round(s * DUTY_PERIOD);
        if (onTicks >= DUTY_PERIOD) {
            return true;
        }
        if (onTicks <= 0) {
            return false;
        }
        return Math.floorMod(dutyTick, DUTY_PERIOD) < onTicks;
    }

    /** Sign of a component once it clears the deadband, else a released key. */
    private static double quantize(double component) {
        return Math.abs(component) > DEADBAND ? Math.signum(component) : 0.0;
    }
}

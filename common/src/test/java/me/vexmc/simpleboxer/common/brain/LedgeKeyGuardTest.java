package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Pins for the quantized-key ledge guard. Geometry: a floor strip whose top is
 * y=65 spanning x ∈ [−4, 1], z ∈ [−4, 8], with void past x=1 deeper than the
 * 3.0-block drop budget. The boxer stands at (0.95, 65, 0.5) — its box spans
 * x ∈ [0.65, 1.25], a rim-walker's stance — with aim yaw 0 (forward = +Z along
 * the edge, strafe = +X over it, {@code ClientPhysics.accelerate}'s frame).
 *
 * <p>Hand-walked probes (LOOK_AHEAD = 0.55, half-width 0.3000000119):
 * the +Z shift keeps box x ∈ [0.65, 1.25] over the strip (ground at 65 —
 * clear); the +X shift puts it at x ∈ [1.2, 1.8] (all void — ledge); the
 * diagonal shifts each axis by 0.55/√2 ≈ 0.389, x ∈ [1.039, 1.639] — past the
 * lip at 1.0, so the WHOLE footprint hangs over void (ledge).</p>
 */
class LedgeKeyGuardTest {

    private static final double EDGE_X = 0.95;
    private static final float YAW_SOUTH = 0.0f;

    private static FakeWorld rimWorld() {
        return FakeWorld.empty().box(new Box(-4, 64, -4, 1, 65, 8));
    }

    private static Box rimStance() {
        return NavGeometry.playerBox(EDGE_X, 65.0, 0.5);
    }

    /** The defect's own numbers: steering approves 0.38 ledge-ward, the deadband
     *  presses both keys (0.38 > 0.35), and the realized diagonal carries 0.707
     *  over the lip. The guard must give back the along-edge key alone. */
    @Test
    void masksTheLedgewardStrafeOutOfAnAmplifiedDiagonal() {
        MoveHeading heading = new MoveHeading(
                new Vec3d(0.38, 0.0, 0.925).horizontalNormalized(), false, 1.0);
        MoveInput quantized = new MotorQuantizer().toInput(
                heading, YAW_SOUTH, true, Intent.JumpHint.NONE, false);
        // The amplification under test: 0.38 clears the 0.35 deadband.
        assertEquals(1.0, quantized.forward(), "the along-edge component must press W");
        assertEquals(1.0, quantized.strafe(), "0.38 ledge-ward must press the strafe key");

        MoveInput masked = LedgeKeyGuard.mask(quantized, heading, YAW_SOUTH,
                rimWorld(), rimStance());
        assertEquals(1.0, masked.forward(), "the safe along-edge key survives");
        assertEquals(0.0, masked.strafe(), "the key over the lip is released");
        assertTrue(masked.sprint(), "sprint passes through untouched");
        assertFalse(masked.jump(), "jump passes through untouched");
    }

    /** With no pressed key pointing to safety, everything is released — a
     *  stopped boxer at the rim beats one over it. */
    @Test
    void releasesEverythingWhenEveryPressedKeyIsLedgeward() {
        MoveHeading heading = new MoveHeading(new Vec3d(1.0, 0.0, 0.0), false, 1.0);
        MoveInput strafeOnly = new MoveInput(0.0, 1.0, false, true, false);

        MoveInput masked = LedgeKeyGuard.mask(strafeOnly, heading, YAW_SOUTH,
                rimWorld(), rimStance());
        assertEquals(0.0, masked.forward());
        assertEquals(0.0, masked.strafe(), "no fabricated keys: the unsafe one just lifts");
        assertTrue(masked.sprint());
    }

    /** Away from the rim the same diagonal keeps ground everywhere — the guard
     *  must not touch it. */
    @Test
    void leavesASafeDiagonalAlone() {
        MoveHeading heading = new MoveHeading(
                new Vec3d(0.38, 0.0, 0.925).horizontalNormalized(), false, 1.0);
        MoveInput diagonal = new MoveInput(1.0, 1.0, false, true, false);
        Box openStance = NavGeometry.playerBox(0.0, 65.0, 0.5);

        MoveInput masked = LedgeKeyGuard.mask(diagonal, heading, YAW_SOUTH,
                rimWorld(), openStance);
        assertEquals(1.0, masked.forward());
        assertEquals(1.0, masked.strafe());
    }
}

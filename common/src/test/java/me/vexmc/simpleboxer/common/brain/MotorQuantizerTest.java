package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Verifies the motor's one hard guarantee (digital keys) and the orbit
 * regression: an aim-perpendicular heading must become a PURE strafe, never a
 * forward+strafe diagonal that spirals the boxer outward.
 */
class MotorQuantizerTest {

    private final MotorQuantizer motor = new MotorQuantizer();

    /** The world direction ClientPhysics.accelerate produces for held keys. */
    private static Vec3d worldDirFor(double forward, double strafe, double yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double dx = strafe * cos - forward * sin;
        double dz = forward * cos + strafe * sin;
        return new Vec3d(dx, 0.0, dz);
    }

    private static boolean isDigital(double v) {
        return v == -1.0 || v == 0.0 || v == 1.0;
    }

    /* (a) THE guarantee: forward and strafe are always digital. */
    @Test
    void everyOutputIsDigitalAcrossRandomHeadingsAndYaws() {
        Random rng = new Random(0xB0_1EDL);
        for (int i = 0; i < 5000; i++) {
            double dx = rng.nextDouble() * 2.0 - 1.0;
            double dz = rng.nextDouble() * 2.0 - 1.0;
            float yaw = (float) (rng.nextDouble() * 720.0 - 360.0);
            MoveHeading heading = new MoveHeading(new Vec3d(dx, 0.0, dz));

            MoveInput in = motor.toInput(heading, yaw, rng.nextBoolean(),
                    Intent.JumpHint.NONE, rng.nextBoolean());

            assertTrue(isDigital(in.forward()), "forward not digital: " + in.forward());
            assertTrue(isDigital(in.strafe()), "strafe not digital: " + in.strafe());
        }
    }

    /* (b) Heading straight along the crosshair -> pure forward. */
    @Test
    void headingAlongFacingIsPureForward() {
        for (float yaw = -350f; yaw <= 350f; yaw += 17f) {
            // Facing-forward world vector for this yaw is (-sin, cos).
            Vec3d dir = worldDirFor(1.0, 0.0, yaw);
            MoveInput in = motor.toInput(new MoveHeading(dir), yaw, false,
                    Intent.JumpHint.NONE, false);

            assertEquals(1.0, in.forward(), "yaw=" + yaw);
            assertEquals(0.0, in.strafe(), "yaw=" + yaw);
        }
    }

    /* (c) Orbit tangent (perpendicular-left of the aim) -> pure strafe, NOT
     * forward=1+strafe=1. This is the spiral-bug regression. */
    @Test
    void perpendicularLeftHeadingIsPureStrafe() {
        for (float yaw = -350f; yaw <= 350f; yaw += 17f) {
            // Left world vector for this yaw is (cos, sin): strafe=+1, forward=0.
            Vec3d dir = worldDirFor(0.0, 1.0, yaw);
            MoveInput in = motor.toInput(new MoveHeading(dir), yaw, true,
                    Intent.JumpHint.NONE, false);

            assertEquals(1.0, in.strafe(), "yaw=" + yaw);
            assertEquals(0.0, in.forward(), "yaw=" + yaw);
        }
    }

    /* (d) Round-trip: build the world dir a key combo produces, feed it back,
     * recover the same signs. */
    @Test
    void roundTripsEveryKeyComboAtSeveralYaws() {
        int[] impulses = {-1, 0, 1};
        for (float yaw : new float[] {0f, 30f, 45f, 90f, 137f, 180f, -45f, -123f, 359f}) {
            for (int f : impulses) {
                for (int s : impulses) {
                    if (f == 0 && s == 0) {
                        continue; // no heading — covered by the still test
                    }
                    Vec3d dir = worldDirFor(f, s, yaw);
                    MoveInput in = motor.toInput(new MoveHeading(dir), yaw, false,
                            Intent.JumpHint.NONE, false);

                    String at = "yaw=" + yaw + " f=" + f + " s=" + s;
                    assertEquals((double) Integer.signum(f), in.forward(), "forward " + at);
                    assertEquals((double) Integer.signum(s), in.strafe(), "strafe " + at);
                }
            }
        }
    }

    /* A still heading releases both movement keys. */
    @Test
    void stillHeadingReleasesMovementKeys() {
        MoveInput in = motor.toInput(MoveHeading.STILL, 42f, true,
                Intent.JumpHint.JUMP, true);
        assertEquals(0.0, in.forward());
        assertEquals(0.0, in.strafe());
        // ... but jump/sprint/sneak still pass through.
        assertTrue(in.jump());
        assertTrue(in.sprint());
        assertTrue(in.sneak());
    }

    /* jump/sprint/sneak flags pass through untouched. */
    @Test
    void jumpSprintSneakPassThrough() {
        Vec3d dir = worldDirFor(1.0, 0.0, 0f);

        MoveInput jumped = motor.toInput(new MoveHeading(dir), 0f, false,
                Intent.JumpHint.JUMP, false);
        assertTrue(jumped.jump());

        MoveInput plain = motor.toInput(new MoveHeading(dir), 0f, false,
                Intent.JumpHint.NONE, false);
        assertTrue(!plain.jump() && !plain.sprint() && !plain.sneak());

        MoveInput flagged = motor.toInput(new MoveHeading(dir), 0f, true,
                Intent.JumpHint.NONE, true);
        assertTrue(flagged.sprint() && flagged.sneak());
    }

    /* Magnitude is irrelevant: a very short (slow) heading still keys fully —
     * pace is a duty-cycle concern, never a fractional impulse. */
    @Test
    void shortHeadingStillKeysFully() {
        Vec3d tiny = worldDirFor(1.0, 0.0, 0f).scale(1.0E-3);
        MoveInput in = motor.toInput(new MoveHeading(tiny, false, 0.1), 0f, false,
                Intent.JumpHint.NONE, false);
        assertEquals(1.0, in.forward());
        assertEquals(0.0, in.strafe());
    }
}

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

    /* speedScale < 1 duty-cycles the DIGITAL forward key across ticks: the average
     * press count tracks the pace, every key stays in {-1,0,1}, and the strafe axis
     * is never touched by the ease-off. This is the previously-dropped no-op. */
    @Test
    void speedScaleDutyCyclesForwardKey() {
        Vec3d fwd = worldDirFor(1.0, 0.0, 0f); // pure forward at yaw 0

        // Half pace: forward held on exactly half the ticks over two full periods.
        int pressedHalf = 0;
        for (int tick = 0; tick < 2 * MotorQuantizer.DUTY_PERIOD; tick++) {
            MoveInput in = motor.toInput(new MoveHeading(fwd, false, 0.5), 0f, true,
                    Intent.JumpHint.NONE, false, tick);
            assertTrue(isDigital(in.forward()), "forward not digital: " + in.forward());
            assertEquals(0.0, in.strafe(), "ease-off must not touch strafe at tick " + tick);
            if (in.forward() == 1.0) {
                pressedHalf++;
            }
        }
        assertEquals(MotorQuantizer.DUTY_PERIOD, pressedHalf,
                "speedScale 0.5 should press forward on half the ticks");

        // Full pace: forward held every tick (no ease-off).
        for (int tick = 0; tick < 2 * MotorQuantizer.DUTY_PERIOD; tick++) {
            MoveInput in = motor.toInput(new MoveHeading(fwd, false, 1.0), 0f, true,
                    Intent.JumpHint.NONE, false, tick);
            assertEquals(1.0, in.forward(), "full speed should always hold forward, tick " + tick);
        }

        // Crawl pace: forward held on a quarter of the ticks (round(0.25*4)=1 of 4).
        int pressedCrawl = 0;
        for (int tick = 0; tick < 2 * MotorQuantizer.DUTY_PERIOD; tick++) {
            MoveInput in = motor.toInput(new MoveHeading(fwd, false, 0.25), 0f, true,
                    Intent.JumpHint.NONE, false, tick);
            if (in.forward() == 1.0) {
                pressedCrawl++;
            }
        }
        assertEquals(2, pressedCrawl, "speedScale 0.25 should press forward a quarter of the ticks");
    }

    /* A near-ledge heading forces sneak — the intended soft edge ease-off — on both
     * overloads, even when the caller passes sneak=false, without disturbing forward. */
    @Test
    void nearLedgePressesSneak() {
        Vec3d fwd = worldDirFor(1.0, 0.0, 0f);
        MoveHeading nearLedge = new MoveHeading(fwd, true, 1.0);

        MoveInput duty = motor.toInput(nearLedge, 0f, false, Intent.JumpHint.NONE, false, 0);
        assertTrue(duty.sneak(), "near a ledge the motor should press sneak");
        assertEquals(1.0, duty.forward(), "full-speed near-ledge still keys forward");

        MoveInput plain = motor.toInput(nearLedge, 0f, false, Intent.JumpHint.NONE, false);
        assertTrue(plain.sneak(), "sneak-on-ledge also holds on the plain overload");

        // A heading NOT near a ledge leaves sneak as the caller passed it.
        MoveInput clear = motor.toInput(new MoveHeading(fwd, false, 1.0), 0f, false,
                Intent.JumpHint.NONE, false, 0);
        assertTrue(!clear.sneak(), "no phantom sneak when clear of ledges");
    }
}

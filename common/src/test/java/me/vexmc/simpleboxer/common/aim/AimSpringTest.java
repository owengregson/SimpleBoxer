package me.vexmc.simpleboxer.common.aim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimSpringTest {

    @Test
    void lockedSnapsInOneTick() {
        AimSpring aim = new AimSpring(AimParams.LOCKED, 0.0f, 0.0f);
        aim.step(73.0f, -12.0f);
        assertEquals(73.0f, aim.yaw(), 1.0E-5f);
        assertEquals(-12.0f, aim.pitch(), 1.0E-5f);
        // No residual momentum: the next tick stays put.
        aim.step(73.0f, -12.0f);
        assertEquals(73.0f, aim.yaw(), 1.0E-5f);
    }

    @Test
    void sharpConvergesWithinAFewTicks() {
        AimSpring aim = new AimSpring(AimParams.SHARP, 0.0f, 0.0f);
        for (int tick = 0; tick < 12; tick++) {
            aim.step(40.0f, 10.0f);
        }
        assertEquals(0.0, aim.yawErrorTo(40.0f), 0.8, "sharp aim settles within 12 ticks");
        assertEquals(10.0f, aim.pitch(), 0.8f);
    }

    @Test
    void sloppyLagsBehindSharp() {
        AimSpring sharp = new AimSpring(AimParams.SHARP, 0.0f, 0.0f);
        AimSpring sloppy = new AimSpring(AimParams.SLOPPY, 0.0f, 0.0f);
        for (int tick = 0; tick < 4; tick++) {
            sharp.step(60.0f, 0.0f);
            sloppy.step(60.0f, 0.0f);
        }
        assertTrue(sloppy.yawErrorTo(60.0f) > sharp.yawErrorTo(60.0f) + 5.0,
                "sloppy aim trails sharp aim early in the flick");
    }

    @Test
    void underdampedSpringOvershootsAReversal() {
        // Track a target far right, then have it flip left: momentum must
        // carry the crosshair PAST the new target before recovering.
        AimSpring aim = new AimSpring(AimParams.SHARP, 0.0f, 0.0f);
        for (int tick = 0; tick < 3; tick++) {
            aim.step(35.0f, 0.0f);
        }
        boolean overshot = false;
        float target = -5.0f;
        for (int tick = 0; tick < 20; tick++) {
            aim.step(target, 0.0f);
            if (aim.yaw() < target - 0.5f) {
                overshot = true;
            }
        }
        assertTrue(overshot, "reversal must overshoot past the new target");
        assertEquals(0.0, aim.yawErrorTo(target), 0.8, "and still settle");
    }

    @Test
    void yawWrapsTheShortWay() {
        AimSpring aim = new AimSpring(AimParams.LOCKED, 170.0f, 0.0f);
        aim.step(-170.0f, 0.0f); // 20° across the seam, not 340° around
        assertEquals(-170.0f, aim.yaw(), 1.0E-4f);
    }

    @Test
    void maxVelocityCapsTheFlick() {
        AimParams capped = new AimParams(1.0, 0.0, 10.0);
        AimSpring aim = new AimSpring(capped, 0.0f, 0.0f);
        // 90°, not 180 — the seam is ambiguous and wraps negative by contract.
        aim.step(90.0f, 0.0f);
        assertEquals(10.0f, aim.yaw(), 1.0E-5f, "one tick moves at most maxVelocity");
    }

    @Test
    void pitchClampsAtStraightDown() {
        AimSpring aim = new AimSpring(AimParams.LOCKED, 0.0f, 80.0f);
        aim.step(0.0f, 200.0f);
        assertEquals(90.0f, aim.pitch(), 1.0E-5f);
    }

    @Test
    void snapKillsMomentum() {
        AimSpring aim = new AimSpring(AimParams.SHARP, 0.0f, 0.0f);
        aim.step(90.0f, 0.0f);
        aim.snapTo(0.0f, 0.0f);
        aim.step(0.0f, 0.0f);
        assertEquals(0.0f, aim.yaw(), 1.0E-5f, "no drift after snap");
    }
}

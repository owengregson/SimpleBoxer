package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe.StrafeDecision;
import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe.StrafeMode;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Deterministic behavior checks for {@link AdaptiveStrafe}. Every case seeds a
 * fresh {@link BrainMemory} so the seeded cadence/jitter is reproducible.
 *
 * <p>Geometry convention: self sits at the origin, the target due east at
 * {@code (3,0)}, so the boxer→target direction is {@code +X} and the {@code +1}
 * (left/CCW) strafe tangent points {@code +Z}. Sign choices are asserted against
 * that fixed frame.
 */
class AdaptiveStrafeTest {

    private final AdaptiveStrafe strafe = new AdaptiveStrafe();

    /** Perception with a live target carrying a signed aim-sweep, velocity and wall flag. */
    private static Perception withTarget(double signedTrackRate, Vec3d oppVelocity,
            boolean horizontalCollision) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, horizontalCollision,
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState target = new Perception.TargetState(
                3, 64, 0, 65.6, oppVelocity,
                /* bearingToMeYaw */ 90.0, Math.abs(signedTrackRate), signedTrackRate,
                /* distance */ 3.0, false);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    /** Convenience: calm aim (no signed sweep) and a still opponent. */
    private static Perception calm(boolean horizontalCollision) {
        return withTarget(0.0, Vec3d.ZERO, horizontalCollision);
    }

    /** Perception with no target (self only). */
    private static Perception noTarget(boolean horizontalCollision) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, horizontalCollision,
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        return new Perception(self, null, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    /** Full-featured adaptive preset (aim break + velocity juke + w-tap sync). */
    private StrafeDecision orbit(Perception p, BrainMemory mem) {
        return strafe.next(p, StrafeMode.ORBIT, AdaptiveStrafe.PARAMS_WTAP_SYNC, mem);
    }

    @Test
    void signedAimPicksTheUncoveredSide() {
        // Opponent sweeping their crosshair positive: break the track by strafing
        // to the +side their aim is lagging away from.
        BrainMemory pos = new BrainMemory(1L);
        pos.strafeSign = -1;
        StrafeDecision d = null;
        for (int t = 0; t < 5; t++) {
            d = orbit(withTarget(20.0, Vec3d.ZERO, false), pos);
        }
        assertEquals(1, d.sign(), "positive sweep should decisively pick the +side");

        // A negative sweep mirrors it.
        BrainMemory neg = new BrainMemory(1L);
        neg.strafeSign = 1;
        StrafeDecision d2 = null;
        for (int t = 0; t < 5; t++) {
            d2 = orbit(withTarget(-20.0, Vec3d.ZERO, false), neg);
        }
        assertEquals(-1, d2.sign(), "negative sweep should decisively pick the -side");
    }

    @Test
    void calmAimBelowThresholdDoesNotTreatNoiseAsATrack() {
        // A sub-threshold sweep must not be read as a lock: with a still opponent
        // the boxer holds a steady circle rather than juking off aim noise.
        BrainMemory mem = new BrainMemory(1234L);
        mem.strafeSign = 1;
        int start = mem.strafeSign;
        boolean flippedOnSignal = false;
        for (int t = 0; t < 4; t++) {
            // below TRACK_THRESHOLD (3 deg/tick), no velocity: no read to exploit.
            StrafeDecision d = orbit(withTarget(1.0, Vec3d.ZERO, false), mem);
            if (t == 0 && d.sign() != start) {
                flippedOnSignal = true;
            }
        }
        // The very first tick must not flip purely because of sub-threshold noise
        // (the slow calm cadence is separate and seeded).
        assertTrue(!flippedOnSignal || mem.strafeSign == start,
                "aim noise under the threshold is not a track");
    }

    @Test
    void jukesOppositeTheOpponentsTangentialMotion() {
        // Opponent sliding along +Z (the +tangent): juke opposite, to the -side,
        // to open the angle their momentum can't follow.
        BrainMemory a = new BrainMemory(2L);
        a.strafeSign = 1;
        StrafeDecision d = null;
        for (int t = 0; t < 3; t++) {
            d = orbit(withTarget(0.0, new Vec3d(0, 0, 1.0), false), a);
        }
        assertEquals(-1, d.sign(), "juke opposite the opponent's +tangent motion");

        BrainMemory b = new BrainMemory(2L);
        b.strafeSign = -1;
        StrafeDecision d2 = null;
        for (int t = 0; t < 3; t++) {
            d2 = orbit(withTarget(0.0, new Vec3d(0, 0, -1.0), false), b);
        }
        assertEquals(1, d2.sign(), "juke opposite the opponent's -tangent motion");
    }

    @Test
    void tightTrackDominatesTheVelocityJuke() {
        // When both signals disagree, the aim-break (they have the read) wins.
        BrainMemory mem = new BrainMemory(5L);
        mem.strafeSign = -1;
        StrafeDecision d = null;
        for (int t = 0; t < 4; t++) {
            // signed sweep wants +1; velocity juke alone would want +1 too here,
            // so bias the velocity the other way to prove the track wins.
            d = orbit(withTarget(20.0, new Vec3d(0, 0, 1.0), false), mem);
        }
        assertEquals(1, d.sign(), "a tight track outranks the velocity juke");
    }

    @Test
    void wtapSyncDefersTheJukeToTheSprintRepress() {
        BrainMemory mem = new BrainMemory(3L);
        mem.strafeSign = -1;
        mem.wtapCountdown = 3; // a w-tap cycle is in flight

        Perception wantsPlus = withTarget(20.0, Vec3d.ZERO, false);
        StrafeDecision deferred = orbit(wantsPlus, mem);
        assertEquals(-1, deferred.sign(), "a pending juke waits while the w-tap cycle runs");

        // The release window ends and the forward key re-presses this tick.
        mem.wtapCountdown = -1;
        mem.wtapReleaseLeft = 0;
        mem.wtapRepressed = true;
        StrafeDecision synced = orbit(wantsPlus, mem);
        assertEquals(1, synced.sign(), "the juke lands exactly on the sprint re-press");
    }

    @Test
    void adaptiveMinDwellForbidsBackToBackFlips() {
        // Flip the demanded side every tick; the seeded min-dwell must still stop
        // two flips landing on adjacent ticks.
        BrainMemory mem = new BrainMemory(42L);
        int prev = mem.strafeSign;
        int consecutiveFlipPairs = 0;
        boolean flippedLastTick = false;
        for (int t = 0; t < 40; t++) {
            double signed = (t % 2 == 0) ? 30.0 : -30.0;
            StrafeDecision d = orbit(withTarget(signed, Vec3d.ZERO, false), mem);
            boolean flippedNow = d.sign() != prev;
            if (flippedNow && flippedLastTick) {
                consecutiveFlipPairs++;
            }
            flippedLastTick = flippedNow;
            prev = d.sign();
        }
        assertEquals(0, consecutiveFlipPairs,
                "the seeded min-dwell must forbid flipping on two adjacent ticks");
    }

    @Test
    void wallInterruptsAdaptiveOrbitImmediately() {
        // Warm to a held side, then a wall must reverse it at once (bypassing dwell).
        BrainMemory mem = new BrainMemory(11L);
        mem.strafeSign = 1;
        orbit(withTarget(20.0, Vec3d.ZERO, false), mem); // settle on +1
        int before = mem.strafeSign;
        StrafeDecision d = orbit(calm(true), mem);
        assertNotEquals(before, d.sign(), "a wall must interrupt the orbit and flip the sign");
    }

    @Test
    void weaveFlipsOnItsShortCadence() {
        BrainMemory mem = new BrainMemory(7L);
        int prev = mem.strafeSign;
        int flips = 0;
        int maxRunBetweenFlips = 0;
        int run = 0;
        for (int t = 0; t < 60; t++) {
            StrafeDecision d = strafe.next(noTarget(false), StrafeMode.WEAVE,
                    AdaptiveStrafe.PARAMS_WEAVE, mem);
            assertEquals(StrafeMode.WEAVE, d.mode());
            if (d.sign() != prev) {
                flips++;
                maxRunBetweenFlips = Math.max(maxRunBetweenFlips, run);
                run = 0;
            } else {
                run++;
            }
            prev = d.sign();
        }
        assertTrue(flips >= 3, "weave should flip repeatedly on its short cadence over 60 ticks");
        assertTrue(maxRunBetweenFlips <= AdaptiveStrafe.WEAVE_MAX,
                "weave gaps must not exceed the short cadence bound");
    }

    @Test
    void noneReturnsModeNoneAndDoesNotChurnState() {
        BrainMemory mem = new BrainMemory(99L);
        int startSign = mem.strafeSign;
        int startFlipIn = mem.strafeFlipIn;
        StrafeDecision d = strafe.next(withTarget(100.0, Vec3d.ZERO, true), StrafeMode.NONE,
                AdaptiveStrafe.PARAMS_WTAP_SYNC, mem);
        assertEquals(StrafeMode.NONE, d.mode());
        assertEquals(startSign, d.sign(), "NONE echoes the current sign");
        assertEquals(startSign, mem.strafeSign, "NONE leaves the persisted sign untouched");
        assertEquals(startFlipIn, mem.strafeFlipIn, "NONE leaves cadence state untouched");
    }

    @Test
    void nonAdaptiveOrbitFlipsImmediatelyOnWall() {
        BrainMemory mem = new BrainMemory(3L);
        strafe.next(noTarget(false), StrafeMode.ORBIT, AdaptiveStrafe.PARAMS_PLAIN, mem); // seed cadence
        int before = mem.strafeSign;
        assertTrue(mem.strafeFlipIn >= AdaptiveStrafe.ORBIT_MIN - 1);
        StrafeDecision d = strafe.next(noTarget(true), StrafeMode.ORBIT, AdaptiveStrafe.PARAMS_PLAIN, mem);
        assertNotEquals(before, d.sign(), "a wall must interrupt the orbit and flip the sign");
    }

    @Test
    void isDeterministicForAFixedSeed() {
        int[] a = run(new BrainMemory(2024L));
        int[] b = run(new BrainMemory(2024L));
        assertTrue(java.util.Arrays.equals(a, b), "same seed must yield the identical sign sequence");
    }

    private int[] run(BrainMemory mem) {
        int[] signs = new int[50];
        for (int t = 0; t < signs.length; t++) {
            // Alternate a tracking/calm read to exercise both the signed-aim and the
            // calm-cadence paths.
            double signed = (t % 3 == 0) ? 25.0 : 0.0;
            signs[t] = orbit(withTarget(signed, Vec3d.ZERO, false), mem).sign();
        }
        return signs;
    }
}

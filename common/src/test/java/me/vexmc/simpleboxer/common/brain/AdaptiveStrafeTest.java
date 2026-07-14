package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe.StrafeDecision;
import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe.StrafeMode;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Deterministic behavior checks for {@link AdaptiveStrafe}. Every case seeds a
 * fresh {@link BrainMemory} so the seeded cadence/jitter is reproducible.
 */
class AdaptiveStrafeTest {

    private final AdaptiveStrafe strafe = new AdaptiveStrafe();

    /** Perception with a live target carrying a given aim-tracking rate and wall flag. */
    private static Perception withTarget(double oppTrackRateDegPerTick, boolean horizontalCollision) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, horizontalCollision,
                1.0, 1.0, Perception.UseItemState.NONE, false);
        Perception.TargetState target = new Perception.TargetState(
                3, 64, 0, 65.6, Vec3d.ZERO,
                /* bearingToMeYaw */ 90.0, oppTrackRateDegPerTick,
                /* distance */ 3.0, false);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    /** Perception with no target (self only). */
    private static Perception noTarget(boolean horizontalCollision) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, horizontalCollision,
                1.0, 1.0, Perception.UseItemState.NONE, false);
        return new Perception(self, null, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    @Test
    void adaptiveFlipsWhenOpponentTracksTightlyButHoldsWhenOpponentLags() {
        int span = 40;

        // High sustained tracking rate: the boxer should reverse at least once
        // across the span (and, thanks to the min-dwell, NOT every single tick).
        BrainMemory tracked = new BrainMemory(1234L);
        Set<Integer> trackedSigns = new HashSet<>();
        int flips = 0;
        int prev = tracked.strafeSign;
        for (int t = 0; t < span; t++) {
            StrafeDecision d = strafe.next(withTarget(20.0, false), StrafeMode.ORBIT, true, tracked);
            trackedSigns.add(d.sign());
            if (d.sign() != prev) {
                flips++;
            }
            prev = d.sign();
        }
        assertEquals(2, trackedSigns.size(),
                "tight tracking should juke to both sides across the span");
        assertTrue(flips >= 1, "should flip at least once under tight tracking");
        assertTrue(flips < span, "min-dwell must stop it flipping every tick");

        // Near-zero tracking rate: the opponent is missing, so hold the sign.
        BrainMemory lagging = new BrainMemory(1234L);
        int startSign = lagging.strafeSign;
        Set<Integer> laggingSigns = new HashSet<>();
        for (int t = 0; t < span; t++) {
            StrafeDecision d = strafe.next(withTarget(0.0, false), StrafeMode.ORBIT, true, lagging);
            laggingSigns.add(d.sign());
        }
        assertEquals(1, laggingSigns.size(), "a lagging opponent should never make us flip");
        assertEquals(startSign, lagging.strafeSign, "sign must hold when the opponent mis-tracks");
    }

    @Test
    void adaptiveMinDwellPreventsBackToBackFlips() {
        BrainMemory mem = new BrainMemory(42L);
        int prev = mem.strafeSign;
        int consecutiveFlipPairs = 0;
        boolean flippedLastTick = false;
        for (int t = 0; t < 30; t++) {
            StrafeDecision d = strafe.next(withTarget(50.0, false), StrafeMode.ORBIT, true, mem);
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
    void weaveFlipsOnItsShortCadence() {
        BrainMemory mem = new BrainMemory(7L);
        int prev = mem.strafeSign;
        int flips = 0;
        int maxRunBetweenFlips = 0;
        int run = 0;
        for (int t = 0; t < 60; t++) {
            StrafeDecision d = strafe.next(noTarget(false), StrafeMode.WEAVE, false, mem);
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
        // Gap between flips must stay within the short WEAVE window (plus the flip tick itself).
        assertTrue(maxRunBetweenFlips <= AdaptiveStrafe.WEAVE_MAX,
                "weave gaps must not exceed the short cadence bound");
    }

    @Test
    void noneReturnsModeNoneAndDoesNotChurnState() {
        BrainMemory mem = new BrainMemory(99L);
        int startSign = mem.strafeSign;
        int startFlipIn = mem.strafeFlipIn;
        StrafeDecision d = strafe.next(withTarget(100.0, true), StrafeMode.NONE, true, mem);
        assertEquals(StrafeMode.NONE, d.mode());
        assertEquals(startSign, d.sign(), "NONE echoes the current sign");
        assertEquals(startSign, mem.strafeSign, "NONE leaves the persisted sign untouched");
        assertEquals(startFlipIn, mem.strafeFlipIn, "NONE leaves cadence state untouched");
    }

    @Test
    void nonAdaptiveOrbitFlipsImmediatelyOnWall() {
        // Warm past the initial flip so strafeFlipIn is a large positive value,
        // proving the wall (not the cadence) is what forces the reversal.
        BrainMemory mem = new BrainMemory(3L);
        strafe.next(noTarget(false), StrafeMode.ORBIT, false, mem); // initial flip seeds cadence
        int before = mem.strafeSign;
        assertTrue(mem.strafeFlipIn >= AdaptiveStrafe.ORBIT_MIN - 1);
        StrafeDecision d = strafe.next(noTarget(true), StrafeMode.ORBIT, false, mem);
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
            // Alternate a tracking/non-tracking read to exercise the adaptive path.
            double rate = (t % 3 == 0) ? 25.0 : 0.0;
            signs[t] = strafe.next(withTarget(rate, false), StrafeMode.ORBIT, true, mem).sign();
        }
        return signs;
    }
}

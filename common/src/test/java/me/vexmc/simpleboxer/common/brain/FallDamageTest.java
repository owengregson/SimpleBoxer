package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the vanilla fall-damage model:
 * {@code maxSafeFallBlocks = floor(safeFall) + floor(budget / ((1 − min(epf,20)/25) × mult))}.
 */
class FallDamageTest {

    @Test
    void noGearBudgetTenIsThirteenBlocks() {
        // factor 1.0 → 3 + floor(10 / 1.0) = 13.
        assertEquals(13, FallDamage.maxSafeFallBlocks(10.0, 0, 3.0, 1.0, false));
    }

    @Test
    void featherFallingFourIsTwentyTwoBlocks() {
        // EPF 12 → factor 0.52 → 3 + floor(10 / 0.52) = 3 + floor(19.23) = 22.
        assertEquals(22, FallDamage.maxSafeFallBlocks(10.0, 12, 3.0, 1.0, false));
    }

    @Test
    void fullProtectionStackCapsAtEpfTwenty() {
        // Feather IV + Prot IV×4 = EPF 28 → capped 20 → factor 0.2 → 3 + floor(50) = 53.
        assertEquals(53, FallDamage.maxSafeFallBlocks(10.0, 28, 3.0, 1.0, false));
    }

    @Test
    void jumpBoostRaisesTheSafeFloor() {
        // Jump Boost II: safe fall 3 + 2 = 5 → 5 + 10 = 15.
        assertEquals(15, FallDamage.maxSafeFallBlocks(10.0, 0, 5.0, 1.0, false));
    }

    @Test
    void fallDamageMultiplierScalesTheFactor() {
        // ×2 multiplier: factor 2.0 → 3 + floor(10 / 2) = 8.
        assertEquals(8, FallDamage.maxSafeFallBlocks(10.0, 0, 3.0, 2.0, false));
    }

    @Test
    void slowFallingIsUnbounded() {
        assertEquals(FallDamage.UNBOUNDED_BLOCKS,
                FallDamage.maxSafeFallBlocks(10.0, 0, 3.0, 1.0, true));
    }

    @Test
    void predictedPointsMatchTheForwardFormula() {
        // 5-block drop, no gear: ceil(5 − 3) × 1.0 = 2 points (one heart).
        assertEquals(2.0, FallDamage.predictedPoints(5.0, 3.0, 0, 1.0, false), 1.0E-9);
        // Feather IV: 2 × 0.52 = 1.04.
        assertEquals(1.04, FallDamage.predictedPoints(5.0, 3.0, 12, 1.0, false), 1.0E-9);
        // At/below the safe floor: zero.
        assertEquals(0.0, FallDamage.predictedPoints(3.0, 3.0, 0, 1.0, false), 1.0E-9);
        // Slow Falling negates outright.
        assertEquals(0.0, FallDamage.predictedPoints(50.0, 3.0, 0, 1.0, true), 1.0E-9);
    }

    @Test
    void inverseIsTightAtTheBudgetEdge() {
        // Feather IV, budget 10 → 22 blocks: ceil(22−3) × 0.52 = 19 × 0.52 = 9.88 ≤ 10,
        // while 23 blocks: 20 × 0.52 = 10.4 > 10 — the floor()ed inverse is exact.
        assertEquals(9.88, FallDamage.predictedPoints(22.0, 3.0, 12, 1.0, false), 1.0E-9);
        assertEquals(10.4, FallDamage.predictedPoints(23.0, 3.0, 12, 1.0, false), 1.0E-9);
    }
}

package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the tick-scaled move-cost model (Baritone's {@code ActionCosts}
 * ported into pure doubles, reconciled against {@link me.vexmc.simpleboxer.common.physics.ClientPhysics}).
 * Every constant here is a physically-derived tick count: 20 ticks/second divided by the
 * block/second travel speed, or the tick-by-tick integral of the vanilla fall/jump arc.
 */
class MoveCostsTest {

    /** 20 ticks/s ÷ 4.317 blocks/s = ticks to walk one block. */
    @Test
    void walkAndSprintAreTicksPerBlock() {
        assertEquals(20.0 / 4.317, MoveCosts.WALK_ONE_BLOCK, 1.0E-12, "walk = 20/4.317");
        assertEquals(4.63285, MoveCosts.WALK_ONE_BLOCK, 1.0E-5, "walk ≈ 4.6328 ticks/block");
        assertEquals(20.0 / 5.612, MoveCosts.SPRINT_ONE_BLOCK, 1.0E-12, "sprint = 20/5.612");
        assertEquals(3.56379, MoveCosts.SPRINT_ONE_BLOCK, 1.0E-5, "sprint ≈ 3.5638 ticks/block");
        // Sprinting is 1.3× faster (ClientPhysics.SPRINT_SPEED_MULTIPLIER), so it costs ~1/1.3 of the
        // ticks. The canonical block/s speeds (4.317, 5.612) are rounded, so the ratio matches 1/1.3
        // only to ~1e-5 (4.317 × 1.3 = 5.6121, not exactly 5.612).
        assertEquals(1.0 / 1.3, MoveCosts.SPRINT_MULTIPLIER, 1.0E-4, "sprint multiplier reconciles to ~1/1.3");
    }

    @Test
    void walkOffAndCenterAfterFallSplitTheBlock() {
        // Walking off a ledge only covers 0.8 of the block before you leave it; the last
        // 0.2 is walked back to centre after landing (CENTER_AFTER_FALL). They sum to WALK.
        assertEquals(MoveCosts.WALK_ONE_BLOCK * 0.8, MoveCosts.WALK_OFF_BLOCK, 1.0E-12);
        assertEquals(3.70628, MoveCosts.WALK_OFF_BLOCK, 1.0E-5, "walk-off ≈ 3.7063");
        assertEquals(MoveCosts.WALK_ONE_BLOCK - MoveCosts.WALK_OFF_BLOCK, MoveCosts.CENTER_AFTER_FALL, 1.0E-12);
        assertEquals(0.92657, MoveCosts.CENTER_AFTER_FALL, 1.0E-5, "center-after-fall ≈ 0.9266");
    }

    /** velocity(t) = (0.98^t − 1)·−3.92 — the distance fallen on tick t of a free fall. */
    @Test
    void fallVelocityMatchesTheVanillaDragIntegral() {
        assertEquals(0.0, MoveCosts.velocity(0), 1.0E-12, "tick 0 of a fall covers no ground (still at the lip)");
        assertEquals(0.0784, MoveCosts.velocity(1), 1.0E-12, "tick 1 falls 0.0784");
        assertEquals(0.155232, MoveCosts.velocity(2), 1.0E-12, "tick 2 falls 0.155232");
    }

    @Test
    void distanceToTicksIntegratesTheFallArc() {
        assertEquals(0.0, MoveCosts.distanceToTicks(0.0), 1.0E-12, "no fall, no ticks");
        // Hand integral of a 1-block fall: 0(t0)+0.0784+0.155232+0.2305274+0.3043128 leaves
        // 0.2315278 to be covered on tick 5 (falls 0.3766305), => 5 + 0.2315278/0.3766305.
        assertEquals(5.614735, MoveCosts.distanceToTicks(1.0), 1.0E-4, "1-block fall ≈ 5.6147 ticks");
        assertEquals(7.78802, MoveCosts.distanceToTicks(2.0), 1.0E-4, "2-block fall ≈ 7.788 ticks");
        // Monotone increasing and self-consistent with the precomputed table.
        assertEquals(0.0, MoveCosts.FALL_N_BLOCKS[0], 1.0E-12, "table[0] is a zero fall");
        assertEquals(MoveCosts.distanceToTicks(1.0), MoveCosts.FALL_N_BLOCKS[1], 1.0E-12, "table matches the formula");
        assertTrue(MoveCosts.FALL_N_BLOCKS[2] > MoveCosts.FALL_N_BLOCKS[1], "deeper falls cost more");
        assertTrue(MoveCosts.FALL_N_BLOCKS[3] > MoveCosts.FALL_N_BLOCKS[2], "monotone");
    }

    @Test
    void jumpOneBlockIsDerivedFromTheClientPhysicsArc() {
        // Standing jump vy0 = 0.42: y after each tick is 0.42, 0.7532, 1.0013… so the feet
        // cross y = 1.0 at 2 + (1.0−0.7532)/0.248136 ≈ 2.9946 ticks.
        assertEquals(2.994616, MoveCosts.jumpTicksToRise(1.0), 1.0E-4, "rise-one-block ≈ 2.9946 ticks");
        assertEquals(MoveCosts.jumpTicksToRise(1.0), MoveCosts.JUMP_ONE_BLOCK, 1.0E-12, "JUMP_ONE_BLOCK is the derived rise");
        // A plain jump apex is ≈ 1.2522 blocks — anything taller is unreachable (COST_INF).
        assertTrue(MoveCosts.jumpTicksToRise(1.25) < MoveCosts.COST_INF, "1.25 blocks is just reachable");
        assertEquals(MoveCosts.COST_INF, MoveCosts.jumpTicksToRise(2.0), 1.0E-9, "2 blocks is beyond a plain jump");
    }

    @Test
    void ascendCostsMoreThanFlatSprintButNotAbsurdly() {
        double ascend = MoveCosts.SPRINT_ONE_BLOCK + MoveCosts.JUMP_ONE_BLOCK;
        assertTrue(ascend > MoveCosts.SPRINT_ONE_BLOCK, "climbing pays a jump penalty over flat travel");
        // Reconciles with Baritone's walk+jumpPenalty(~2) ≈ 6.6 ticks for an ascend.
        assertEquals(6.558, ascend, 1.0E-2, "ascend ≈ 6.56 ticks");
    }

    @Test
    void fallTicksInterpolatesBetweenTableEntries() {
        assertEquals(MoveCosts.FALL_N_BLOCKS[1], MoveCosts.fallTicks(1.0), 1.0E-12, "integer falls hit the table");
        double half = MoveCosts.fallTicks(1.5);
        assertTrue(half > MoveCosts.FALL_N_BLOCKS[1] && half < MoveCosts.FALL_N_BLOCKS[2],
                "a 1.5-block fall lies between the 1- and 2-block table entries");
    }

    // --- WS-NAV: fall-tick pins for the deep-drop cost model -------------------
    // distanceToTicks integrates v(t) = (0.98^t − 1)·(−3.92) per tick and credits
    // the last tick fractionally. Cumulative blocks fallen after t ticks:
    // S8 = 2.694561, S11 = 4.844478, S17 = 10.806525 (v9 = 0.651709,
    // v12 = 0.843910, v18 = 1.195070).

    @Test
    void fallTicksOfThreeBlocks() {
        // 3 − S8 = 0.305439, /v9 = 0.4687 → 9.4687 ticks.
        assertEquals(9.4687, MoveCosts.fallTicks(3.0), 1.0E-3);
    }

    @Test
    void fallTicksOfFiveBlocks() {
        // 5 − S11 = 0.155522, /v12 = 0.1843 → 12.1843 ticks.
        assertEquals(12.1843, MoveCosts.fallTicks(5.0), 1.0E-3);
    }

    @Test
    void fallTicksOfTwelveBlocks() {
        // 12 − S17 = 1.193475, /v18 = 0.9987 → 18.9987 ticks. As in the 3- and
        // 5-block pins above, the whole part is the INDEX OF THE CONSUMED VELOCITY
        // SAMPLE (v18 here): tick 0 covers no ground (Baritone's leave-the-lip
        // convention, pinned by fallVelocityMatchesTheVanillaDragIntegral), so the
        // counter runs one ahead of the physical 17.9987 falling ticks.
        assertEquals(18.9987, MoveCosts.fallTicks(12.0), 1.0E-3);
    }
}

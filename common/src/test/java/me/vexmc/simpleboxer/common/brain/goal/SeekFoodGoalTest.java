package me.vexmc.simpleboxer.common.brain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Verifies the seek-food routine: the trigger gate (natural hunger + food + target +
 * hunger below the eat threshold), the exclusive latch that holds the boxer until it is
 * full again, and the full swap → eat → hold → swap-back FSM (standing still while it eats).
 */
class SeekFoodGoalTest {

    private static final BoxerSettings NATURAL = BoxerSettings.DEFAULTS
            .withHunger(new BoxerSettings.Hunger(true, 14));

    private static Supplier<BoxerSettings> supplier(BoxerSettings s) {
        return () -> s;
    }

    /**
     * A crafted perception. {@code hungerPct} is 0..1 (×20 = the 0..20 hunger bar); the
     * target sits {@code distance} blocks away on +x so retreat heads toward -x.
     */
    private static Perception perc(double hungerPct, boolean hasFood, boolean hasTarget,
            double distance) {
        Perception.SelfState self = new Perception.SelfState(
                0.0, 64.0, 0.0, Vec3d.ZERO, true, false, 1.0, hungerPct,
                Perception.UseItemState.NONE, false, 0.1, -1, 20.0, 0, 3.0, 1.0, false);
        Perception.TargetState target = hasTarget
                ? new Perception.TargetState(distance, 64.0, 0.0, 65.6, Vec3d.ZERO,
                        0.0, 0.0, 0.0, distance, false)
                : null;
        Perception.InventoryView inv = new Perception.InventoryView(
                true, false, false, hasFood, false, 0);
        return new Perception(self, target, Perception.TerrainView.OPEN, inv,
                Perception.CombatState.IDLE, 0);
    }

    // (a) utility is zero when the routine is not eligible.

    @Test
    void zeroUtilityWhenNaturalHungerOff() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(BoxerSettings.DEFAULTS)); // natural = false
        // Hungry, with food and a target — but natural hunger is off.
        assertEquals(0.0, g.utility(perc(0.4, true, true, 3.0)));
        assertFalse(g.exclusive(perc(0.4, true, true, 3.0)));
    }

    @Test
    void zeroUtilityWhenNoFood() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        assertEquals(0.0, g.utility(perc(0.4, false, true, 3.0)));
    }

    @Test
    void zeroUtilityWhenNoTarget() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        assertEquals(0.0, g.utility(perc(0.4, true, false, 3.0)));
    }

    @Test
    void zeroUtilityWhenNotHungry() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        // Full (20/20) and comfortably full (18/20) both release the latch.
        assertEquals(0.0, g.utility(perc(1.0, true, true, 3.0)));
        assertEquals(0.0, g.utility(perc(0.9, true, true, 3.0))); // 18/20
        assertFalse(g.exclusive(perc(1.0, true, true, 3.0)));
    }

    // (b) high utility + exclusive when hungry, with hysteresis all the way to full.

    @Test
    void highAndExclusiveWhenHungry() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        // 8/20 <= eatThreshold(14) -> peak, and above ordinary combat (0.5).
        Perception hungry = perc(0.4, true, true, 3.0);
        assertEquals(SeekFoodGoal.PEAK_UTILITY, g.utility(hungry));
        assertTrue(g.utility(hungry) > 0.5);
        assertTrue(g.exclusive(hungry));
        // Exactly at the eat threshold (14/20) still fires at peak.
        assertEquals(SeekFoodGoal.PEAK_UTILITY, g.utility(perc(0.7, true, true, 3.0)));
    }

    @Test
    void hysteresisHoldsThroughEatingUntilFull() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        // Between eat threshold (14) and full (18): a positive latch plateau that keeps
        // the routine exclusive but stays below ordinary combat so it never newly triggers.
        for (double hunger : new double[] {14.5, 16.0, 17.9}) {
            Perception p = perc(hunger / 20.0, true, true, 3.0);
            double u = g.utility(p);
            assertEquals(SeekFoodGoal.LATCH_UTILITY, u, "hunger=" + hunger);
            assertTrue(u > 0.0, "latch positive at hunger=" + hunger);
            assertTrue(u < 0.5, "latch below combat at hunger=" + hunger);
            assertTrue(g.exclusive(p), "still exclusive at hunger=" + hunger);
        }
        // At full (18/20) the latch releases.
        Perception full = perc(SeekFoodGoal.FULL_HUNGER / 20.0, true, true, 3.0);
        assertEquals(0.0, g.utility(full));
        assertFalse(g.exclusive(full));
    }

    // (c) the full FSM: back off -> swap food -> start eating -> hold -> release -> swap weapon.

    @Test
    void fsmBacksOffEatsAndSwapsBack() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        BrainMemory mem = new BrainMemory(1L);

        // Phase 0: still too close (2 blocks) -> keep backing off, sprinting, no item action.
        Intent i0 = g.decide(perc(0.4, true, true, 2.0), mem);
        assertInstanceOf(Intent.ActionIntent.None.class, i0.action());
        assertTrue(i0.wantSprint());
        assertTrue(i0.moveDirWorld().lengthSqr() > 0.0, "backs off with a real heading");

        // Phase 0 -> arms the swap once far enough (5 blocks > 4). This tick still backs off.
        Intent i1 = g.decide(perc(0.4, true, true, 5.0), mem);
        assertInstanceOf(Intent.ActionIntent.None.class, i1.action());

        // Phase 1: swap to the food slot (default 3).
        Intent i2 = g.decide(perc(0.4, true, true, 5.0), mem);
        Intent.ActionIntent.SelectSlot toFood =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, i2.action());
        assertEquals(3, toFood.slot());

        // Phase 2: begin eating — StartUse and stand still.
        Intent i3 = g.decide(perc(0.4, true, true, 5.0), mem);
        Intent.ActionIntent.StartUse start =
                assertInstanceOf(Intent.ActionIntent.StartUse.class, i3.action());
        assertTrue(start.mainHand());
        assertSame(Vec3d.ZERO, i3.moveDirWorld(), "stands still to eat");

        // Phase 3: hold and eat for the full duration, standing still, not swinging.
        for (int tick = 0; tick < 33; tick++) {
            Intent hold = g.decide(perc(0.4, true, true, 5.0), mem);
            assertInstanceOf(Intent.ActionIntent.None.class, hold.action(),
                    "no action while chewing, tick " + tick);
            assertSame(Vec3d.ZERO, hold.moveDirWorld(), "stands still while eating, tick " + tick);
        }

        // The 34th hold tick releases the use.
        Intent release = g.decide(perc(0.4, true, true, 5.0), mem);
        assertInstanceOf(Intent.ActionIntent.ReleaseUse.class, release.action());
        assertSame(Vec3d.ZERO, release.moveDirWorld());

        // Phase 4: swap the weapon back (default slot 0) and reset the FSM.
        Intent swapBack = g.decide(perc(0.9, true, true, 5.0), mem);
        Intent.ActionIntent.SelectSlot toWeapon =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, swapBack.action());
        assertEquals(0, toWeapon.slot());

        // Reset lands back in phase 0: a close target draws a fresh back-off intent.
        Intent afterReset = g.decide(perc(0.4, true, true, 2.0), mem);
        assertInstanceOf(Intent.ActionIntent.None.class, afterReset.action());
        assertTrue(afterReset.wantSprint());
    }

    /**
     * A same-speed chaser holds the gap constant, so the {@code BACKOFF_DISTANCE}
     * gate never becomes true. The back-off phase must still time out and reach the
     * eat phase within the tick cap, or the boxer (exclusive + suppressesAttack)
     * back-pedals forever and never eats.
     */
    @Test
    void backOffTimesOutAndEatsWhenChaserHoldsDistance() {
        SeekFoodGoal g = new SeekFoodGoal(supplier(NATURAL));
        BrainMemory mem = new BrainMemory(7L);
        // Distance pinned BELOW the back-off radius (4.0): the gap never opens.
        Perception pinned = perc(0.4, true, true, 2.0);

        boolean beganEating = false;
        for (int tick = 0; tick < SeekFoodGoal.BACKOFF_TICK_CAP + 5 && !beganEating; tick++) {
            Intent intent = g.decide(pinned, mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                beganEating = true;
            }
        }
        assertTrue(beganEating, "eating must begin despite the chaser holding the distance constant");
    }
}

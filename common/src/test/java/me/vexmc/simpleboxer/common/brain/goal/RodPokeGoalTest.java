package me.vexmc.simpleboxer.common.brain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Verifies the rod-poke trigger window and the raise → cast → swap-back FSM:
 * the utility is silent unless the feature is on, a rod is carried, the target is
 * in the rod band AND closing fast, and the routine is off cooldown; and one poke
 * arms a cooldown that silences the utility so Engage can combo the opening.
 */
class RodPokeGoalTest {

    // Default rod band is [3, 6]; rod slot 1, weapon slot 0.
    private static BoxerSettings enabled() {
        return BoxerSettings.DEFAULTS.withCombat(
                new BoxerSettings.Combat(false, true, 3.0, 6.0,
                        BoxerSettings.Combat.StrafePreset.NONE, false, 0.0));
    }

    private static Supplier<BoxerSettings> sup(BoxerSettings s) {
        return () -> s;
    }

    /**
     * Boxer at the origin; target 4 blocks out along +X (in band), moving with the
     * given velocity. hasRod toggles the inventory rod flag; distance is set
     * explicitly so callers can push the target out of the band without moving it.
     */
    private static Perception perception(boolean hasRod, double distance, Vec3d targetVel) {
        Perception.SelfState self = new Perception.SelfState(
                0, 0, 0, Vec3d.ZERO, true, false, 1.0, 1.0,
                Perception.UseItemState.NONE, false, 0.1, -1);
        Perception.TargetState target = new Perception.TargetState(
                4.0, 0.0, 0.0, 1.6, targetVel, 0.0, 0.0, 0.0, distance, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, hasRod, false, false, false, 0);
        return new Perception(self, target, Perception.TerrainView.OPEN, inv,
                Perception.CombatState.IDLE, 0);
    }

    /** A rusher: velocity points from the target back toward the boxer (-X). */
    private static Perception closingInBand(boolean hasRod) {
        return perception(hasRod, 4.0, new Vec3d(-0.3, 0.0, 0.0));
    }

    // ---- (a) utility is zero when it must not fire ----------------------------

    @Test
    void zeroWhenFeatureDisabled() {
        RodPokeGoal g = new RodPokeGoal(sup(BoxerSettings.DEFAULTS)); // Combat.OFF
        assertEquals(0.0, g.utility(closingInBand(true)));
    }

    @Test
    void zeroWhenNoRod() {
        RodPokeGoal g = new RodPokeGoal(sup(enabled()));
        assertEquals(0.0, g.utility(closingInBand(false)));
    }

    @Test
    void zeroWhenOutOfBand() {
        RodPokeGoal g = new RodPokeGoal(sup(enabled()));
        // Below the band and above it, both silent even while closing.
        assertEquals(0.0, g.utility(perception(true, 1.5, new Vec3d(-0.3, 0.0, 0.0))));
        assertEquals(0.0, g.utility(perception(true, 9.0, new Vec3d(-0.3, 0.0, 0.0))));
    }

    @Test
    void zeroWhenTargetReceding() {
        RodPokeGoal g = new RodPokeGoal(sup(enabled()));
        // Velocity points away from the boxer (+X): inbound component is negative.
        assertEquals(0.0, g.utility(perception(true, 4.0, new Vec3d(0.3, 0.0, 0.0))));
        // A near-stationary target (below the closing threshold) is also silent.
        assertEquals(0.0, g.utility(perception(true, 4.0, new Vec3d(-0.05, 0.0, 0.0))));
    }

    // ---- (b) utility is high in the trigger window ----------------------------

    @Test
    void highWhenEnabledInBandClosingOffCooldown() {
        RodPokeGoal g = new RodPokeGoal(sup(enabled()));
        double u = g.utility(closingInBand(true));
        assertEquals(RodPokeGoal.POKE_UTILITY, u);
        assertTrue(u > 0.5, "a live poke must out-score ordinary Engage (0.5)");
    }

    // ---- (c) the FSM sequence and the cooldown --------------------------------

    @Test
    void castSequenceThenCooldownSilencesUtility() {
        BoxerSettings s = enabled();
        RodPokeGoal g = new RodPokeGoal(sup(s));
        BrainMemory mem = new BrainMemory(0L);
        Perception p = closingInBand(true);

        int rodSlot = s.items().rodSlot();     // 1
        int weaponSlot = s.items().weaponSlot(); // 0

        // Phase 0 — raise the rod: SelectSlot(rodSlot).
        Intent i0 = g.decide(p, mem);
        Intent.ActionIntent.SelectSlot raise =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, i0.action());
        assertEquals(rodSlot, raise.slot());
        assertEquals(1, mem.ints("rodPoke", 2)[0], "phase advances to cast");

        // Phase 1 — cast: StartUse(mainHand).
        Intent i1 = g.decide(p, mem);
        Intent.ActionIntent.StartUse cast =
                assertInstanceOf(Intent.ActionIntent.StartUse.class, i1.action());
        assertTrue(cast.mainHand(), "the rod casts from the main hand");
        assertEquals(2, mem.ints("rodPoke", 2)[0], "phase advances to swap-back");

        // Phase 2 — swap back: SelectSlot(weaponSlot) and arm the cooldown.
        Intent i2 = g.decide(p, mem);
        Intent.ActionIntent.SelectSlot back =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, i2.action());
        assertEquals(weaponSlot, back.slot());
        assertEquals(0, mem.ints("rodPoke", 2)[0], "phase wraps back to raise");
        assertEquals(RodPokeGoal.COOLDOWN_TICKS, mem.ints("rodPoke", 2)[1],
                "the swap-back arms the cooldown");

        // An immediate re-eval reads 0 utility — Engage takes over for the combo,
        // even though the target is still in band and closing.
        assertEquals(0.0, g.utility(p), "the cooldown silences the routine");

        // The cooldown bleeds down each decide and, once served, the routine fires
        // again from phase 0.
        for (int tick = 0; tick < RodPokeGoal.COOLDOWN_TICKS; tick++) {
            Intent idle = g.decide(p, mem);
            assertInstanceOf(Intent.ActionIntent.None.class, idle.action());
        }
        assertEquals(0, mem.ints("rodPoke", 2)[1], "the cooldown has drained");
        assertEquals(RodPokeGoal.POKE_UTILITY, g.utility(p), "the routine re-arms");
    }

    /**
     * The whole point of the poke is its knockback — which reverses the target's
     * velocity. If {@code utility()} let that reversal (or the range it opens) pull
     * the score to 0 mid-cast, the arbiter would forfeit the dwell after the cast and
     * hand control to Engage BEFORE the swap-back-to-weapon + cooldown-arm phase ran,
     * stranding the boxer swinging the rod in melee. Once the FSM has left idle the
     * utility must stay positive so all three phases complete.
     */
    @Test
    void midCastStaysScoredEvenWhenKnockbackReversesClosing() {
        BoxerSettings s = enabled();
        RodPokeGoal g = new RodPokeGoal(sup(s));
        BrainMemory mem = new BrainMemory(0L);

        Perception closing = closingInBand(true);                       // velocity -X (inbound)
        Perception knockedBack = perception(true, 4.0, new Vec3d(0.3, 0.0, 0.0)); // velocity +X

        // Phase 0 -> 1: raise the rod (still closing).
        assertTrue(g.utility(closing) > 0.0);
        g.decide(closing, mem);
        assertEquals(1, mem.ints("rodPoke", 2)[0]);

        // Phase 1 -> 2: cast (still closing at this instant).
        assertTrue(g.utility(closing) > 0.0);
        g.decide(closing, mem);
        assertEquals(2, mem.ints("rodPoke", 2)[0]);

        // The hook's knockback now reverses the target's velocity. Pre-fix the
        // closing gate zeroed the score here; post-fix the mid-sequence override
        // keeps it positive so phase 2 runs.
        assertEquals(RodPokeGoal.POKE_UTILITY, g.utility(knockedBack),
                "mid-sequence utility must ignore the closing gate the poke itself defeats");

        // Phase 2: swap back to the weapon and arm the cooldown — the sequence completes.
        Intent swapBack = g.decide(knockedBack, mem);
        Intent.ActionIntent.SelectSlot back =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, swapBack.action());
        assertEquals(s.items().weaponSlot(), back.slot(), "swaps back to the weapon");
        assertEquals(0, mem.ints("rodPoke", 2)[0], "phase wraps back to idle");
        assertEquals(RodPokeGoal.COOLDOWN_TICKS, mem.ints("rodPoke", 2)[1],
                "the swap-back arms the cooldown");
    }

    @Test
    void suppressesAttackWhileHoldingRod() {
        RodPokeGoal g = new RodPokeGoal(sup(enabled()));
        assertTrue(g.suppressesAttack());
        assertEquals(3, g.minDwellTicks());
    }
}

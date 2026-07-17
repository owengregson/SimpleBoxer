package me.vexmc.simpleboxer.common.brain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Pins for {@link EngageGoal}'s circle-strafe: it must begin sidestepping from
 * well outside the ring (not only once inside ~4.25 blocks) and, in the ring
 * band, hold a strongly tangential heading rather than diluting it with forward.
 *
 * <p>Frame: self at the origin, target due east at {@code (distance, 0)}, so the
 * boxer→target direction is {@code +X} and the strafe tangent is along {@code Z}.
 */
class EngageGoalTest {

    private static final double RADIUS = 2.75; // EngageGoal.DEFAULT_ORBIT_RADIUS

    private static BoxerSettings circleSettings() {
        return BoxerSettings.DEFAULTS.withMovement(
                new BoxerSettings.Movement(BoxerSettings.Movement.Style.STRAFE_CIRCLE, 0.0, true));
    }

    private static Perception at(double distance) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState target = new Perception.TargetState(
                distance, 64, 0, 65.62, Vec3d.ZERO,
                90.0, 0.0, 0.0, distance, false);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    /** Lateral (tangential, |Z|) share of a normalized move heading. */
    private static double lateral(Vec3d move) {
        return Math.abs(move.z());
    }

    /** Forward (toward-target, +X) share of a normalized move heading. */
    private static double forward(Vec3d move) {
        return move.x();
    }

    @Test
    void sidestepsSoonerThanTheOldFourAndAQuarterBlockGate() {
        BoxerSettings s = circleSettings();
        EngageGoal goal = new EngageGoal(() -> s, new AdaptiveStrafe());
        BrainMemory mem = new BrainMemory(1L);

        // 5.0 blocks is beyond the old radius+1.5 (=4.25) pure-approach gate: the
        // boxer must already be carrying a real tangential component here.
        Intent intent = goal.decide(at(5.0), mem);
        assertTrue(lateral(intent.moveDirWorld()) > 0.3,
                "at 5 blocks the boxer should already be sidestepping, not just closing");
    }

    @Test
    void inBandHeadingIsStronglyTangential() {
        BoxerSettings s = circleSettings();
        EngageGoal goal = new EngageGoal(() -> s, new AdaptiveStrafe());
        BrainMemory mem = new BrainMemory(2L);

        double minLateral = 1.0;
        for (int t = 0; t < 5; t++) {
            Intent intent = goal.decide(at(RADIUS), mem);
            Vec3d move = intent.moveDirWorld();
            minLateral = Math.min(minLateral, lateral(move));
            assertTrue(lateral(move) >= Math.abs(forward(move)) - 1.0E-9,
                    "in-band heading must be at least as tangential as it is forward");
        }
        assertTrue(minLateral > 0.9,
                "reduced forward dilution should keep the in-band heading strongly tangential");
    }

    /* ---- Weapon-hand baseline: routines restore their slot only in their own
     * exit phase, so an interrupted routine (death mid-heal is the canonical
     * case) leaves its tool slot selected; Engage re-selects on mismatch,
     * throttled to one in-flight command per 10 decisions. ---- */

    private static final int WEAPON_SLOT = BoxerSettings.DEFAULTS.items().weaponSlot();

    /** As {@link #at(double)} but holding {@code selectedSlot}. */
    private static Perception holding(int selectedSlot) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState target = new Perception.TargetState(
                6.0, 64, 0, 65.62, Vec3d.ZERO,
                90.0, 0.0, 0.0, 6.0, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, true, true, true, selectedSlot);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                inv, Perception.CombatState.IDLE, 0);
    }

    @Test
    void reselectsTheWeaponSlotWhenHoldingAnotherSlot() {
        EngageGoal goal = new EngageGoal(() -> BoxerSettings.DEFAULTS, new AdaptiveStrafe());
        BrainMemory mem = new BrainMemory(42L);
        int stale = (WEAPON_SLOT + 2) % 9; // e.g. the pot slot of a heal death ended

        Intent.ActionIntent action = goal.decide(holding(stale), mem).action();
        Intent.ActionIntent.SelectSlot select =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, action);
        assertEquals(WEAPON_SLOT, select.slot());
    }

    @Test
    void throttlesTheReselectWhileTheCommandIsInFlight() {
        EngageGoal goal = new EngageGoal(() -> BoxerSettings.DEFAULTS, new AdaptiveStrafe());
        BrainMemory mem = new BrainMemory(42L);
        int stale = (WEAPON_SLOT + 2) % 9;

        assertInstanceOf(Intent.ActionIntent.SelectSlot.class,
                goal.decide(holding(stale), mem).action(), "decision 1 fires");
        // Decisions 2..10 sit inside the retry window: the first command is
        // still riding the action latency line, so nothing re-fires.
        for (int decision = 2; decision <= 10; decision++) {
            assertInstanceOf(Intent.ActionIntent.None.class,
                    goal.decide(holding(stale), mem).action(),
                    "decision " + decision + " is throttled");
        }
        // Decision 11: the window elapsed and the slot is STILL wrong — retry.
        assertInstanceOf(Intent.ActionIntent.SelectSlot.class,
                goal.decide(holding(stale), mem).action(), "decision 11 retries");
    }

    @Test
    void holdsTheWeaponSlotQuietly() {
        EngageGoal goal = new EngageGoal(() -> BoxerSettings.DEFAULTS, new AdaptiveStrafe());
        BrainMemory mem = new BrainMemory(42L);
        assertInstanceOf(Intent.ActionIntent.None.class,
                goal.decide(holding(WEAPON_SLOT), mem).action());
    }

    @Test
    void respawnResetClearsRoutineScratchRouteAndComboState() {
        BrainMemory mem = new BrainMemory(42L);
        mem.ints("potHeal", 5)[0] = 3; // a mid-FSM phase a dead boxer must not resume
        mem.path = java.util.List.of(new Vec3d(1, 64, 1));
        mem.pathCursor = 1;
        mem.climbLatch = true;
        mem.wtapReleaseLeft = 2;
        mem.incumbentGoal = "potHeal";

        mem.onRespawn();

        assertEquals(0, mem.ints("potHeal", 5)[0], "routine FSMs restart from phase 0");
        assertNull(mem.path, "no committed route survives the respawn");
        assertEquals(0, mem.pathCursor);
        assertFalse(mem.climbLatch);
        assertEquals(0, mem.wtapReleaseLeft, "no combo state survives");
        assertNull(mem.incumbentGoal, "arbitration restarts from the baseline");
    }
}

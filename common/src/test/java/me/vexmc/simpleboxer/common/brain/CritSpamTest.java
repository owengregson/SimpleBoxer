package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.brain.Perception.CombatState;
import me.vexmc.simpleboxer.common.brain.Perception.InventoryView;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TargetState;
import me.vexmc.simpleboxer.common.brain.Perception.TerrainView;
import me.vexmc.simpleboxer.common.brain.Perception.UseItemState;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Behavioral contract for {@link CritSpam}: the pulsed bonk-hop cycle under a
 * crit roof (simulated against the REAL integrator), the eligibility latch
 * across the airborne half of the cycle, the sprint-drop window, and every
 * activation gate. The hop expectations are hand-computed from the vanilla
 * constants (0.42 impulse, ×0.98 vertical drag, −0.08 gravity) — the same
 * arithmetic docs/research/2026-06-06-client-motion-pins.md pins.
 */
class CritSpamTest {

    private static final BoxerSettings CRIT_ON =
            BoxerSettings.DEFAULTS.withCritSpam(BoxerSettings.CritSpam.ON);

    /** Floor top at y = 0 plus a roof underside at y = 3: the canonical 1.2 gap. */
    private static FakeWorld threeBlockRoom() {
        return FakeWorld.floorAt(0.0).box(new Box(-8.0, 3.0, -8.0, 8.0, 4.0, 8.0));
    }

    /** Settle onto the floor: y = 0, vy at the −0.0784 grounded equilibrium. */
    private static ClientPhysics settle(CollisionView world) {
        ClientPhysics physics = new ClientPhysics(0.0, 0.0, 0.0);
        for (int tick = 0; tick < 3 && !physics.onGround(); tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
        }
        return physics;
    }

    /** The brain-side view of the sim: live self kinematics + a pocket target 2 blocks out. */
    private static Perception perceive(ClientPhysics physics, long serverTick) {
        SelfState self = new SelfState(physics.x(), physics.y(), physics.z(),
                physics.velocity(), physics.onGround(), physics.horizontalCollision(),
                1.0, 1.0, UseItemState.NONE, false, 0.1, -1, 20.0, 0, 3.0, 1.0, false);
        TargetState target = new TargetState(0.0, physics.y(), 2.0, physics.y() + 1.62,
                Vec3d.ZERO, 0.0, 0.0, 0.0, 2.0, false);
        return new Perception(self, target, TerrainView.OPEN, InventoryView.EMPTY,
                new CombatState(1.0, false, serverTick, 0), 0);
    }

    /** A hand-crafted snapshot, for the activation-gate cases (no integrator). */
    private static Perception crafted(double y, Vec3d velocity, boolean onGround,
            double targetDistance, long serverTick) {
        SelfState self = new SelfState(0.0, y, 0.0, velocity, onGround, false,
                1.0, 1.0, UseItemState.NONE, false, 0.1, -1, 20.0, 0, 3.0, 1.0, false);
        TargetState target = new TargetState(0.0, y, targetDistance, y + 1.62,
                Vec3d.ZERO, 0.0, 0.0, 0.0, targetDistance, false);
        return new Perception(self, target, TerrainView.OPEN, InventoryView.EMPTY,
                new CombatState(1.0, false, serverTick, 0), 0);
    }

    private static void assertInactive(CritSpam.Decision d, BrainMemory mem, long tick) {
        assertEquals(JumpHint.NONE, d.jump());
        assertFalse(d.dropSprint());
        assertFalse(CritSpam.activeThisTick(mem, tick), "inactive module leaves no stamp");
    }

    // The canonical cycle, closed-loop against the real integrator:
    //   t1  press (grounded)   rise 0.42            -> y 0.42
    //   t2..t4 released        rises 0.3332, 0.248136, 0.16477328 -> y 1.16610928
    //   t5  released           rise clipped at the roof: 3.0 - (1.16610928 +
    //                          PLAYER_HEIGHT 1.7999999523162842) = 0.03389077 ->
    //                          y 3.0 - PLAYER_HEIGHT (the box top flush at the
    //                          underside), vy zeroed, then gravity+drag give
    //                          (0 - 0.08) x 0.98 = -0.0784 -- the bonk, NOT a
    //                          landing (onGround needs vy < 0 at the collision)
    //   t6..t10 released       falls 0.0784, 0.155232, 0.23052736, 0.30431681,
    //                          0.37663048 -> y 0.054893350656
    //   t11 released           the last 0.0549 is clipped by the floor -> y 0,
    //                          grounded
    //   t12 press again        the pulse re-launches on the FIRST grounded tick
    //                          (the airborne release reset noJumpDelay)
    @Test
    void hopCycleUnderAThreeBlockRoof() {
        CollisionView world = threeBlockRoom();
        ClientPhysics physics = settle(world);
        CritSpam critSpam = new CritSpam();
        BrainMemory mem = new BrainMemory(7L);

        boolean[] pressed = new boolean[14];
        boolean[] window = new boolean[14];
        for (int t = 1; t <= 13; t++) {
            Perception p = perceive(physics, t);
            CritSpam.Decision d = critSpam.evaluate(p, "engage", CRIT_ON, world, mem);
            assertTrue(CritSpam.activeThisTick(mem, t), "active on every tick of the cycle");
            pressed[t] = d.jump() == JumpHint.JUMP;
            window[t] = d.dropSprint();
            assertEquals(CritSpam.critWindowAtArrival(p), d.dropSprint(),
                    "sprint drops exactly on the crit window");
            physics.step(new MoveInput(0.0, 0.0, pressed[t], false, false), 0.0f, world);
            if (t == 5) {
                assertEquals(3.0 - ClientPhysics.PLAYER_HEIGHT, physics.y(), 1.0E-9,
                        "apex clipped at the roof");
                assertEquals(-0.0784, physics.velocity().y(), 1.0E-9, "post-bonk descent");
                assertFalse(physics.onGround(), "a ceiling bonk is not a landing");
            }
            if (t == 11) {
                assertEquals(0.0, physics.y(), 1.0E-12, "landed");
                assertTrue(physics.onGround());
            }
        }
        for (int t = 1; t <= 13; t++) {
            assertEquals(t == 1 || t == 12, pressed[t],
                    "jump is a PULSE: launch tick and first grounded tick only (t=" + t + ")");
            assertEquals(t >= 6 && t <= 11, window[t],
                    "crit window = the 6 descending decision ticks of the 11-tick hop (t=" + t + ")");
        }
    }

    // The load-bearing pulse contract, pinned straight on ClientPhysics under a
    // 0.7 gap (roof underside 2.5): rise 0.42 at t1; t2 clipped at 0.7 (bonk);
    // falls 0.0784, 0.155232, 0.23052736 to y 0.23584064; t6 clips the rest ->
    // landed. A PULSED key relaunches at t7. A HELD key set noJumpDelay = 10 at
    // t1 and only decrements once per tick, reaching 0 on t11 -- four grounded
    // ticks (t7-t10) are wasted flat on the floor.
    @Test
    void pulsedJumpKeySidestepsTheHeldKeyDelay() {
        CollisionView room = FakeWorld.floorAt(0.0)
                .box(new Box(-8.0, 2.5, -8.0, 8.0, 3.5, 8.0));

        ClientPhysics pulsed = settle(room);
        for (int t = 1; t <= 7; t++) {
            pulsed.step(new MoveInput(0.0, 0.0, pulsed.onGround(), false, false), 0.0f, room);
        }
        assertEquals(0.42, pulsed.y(), 1.0E-9,
                "pulsed key re-launches on t7, the first grounded tick after the t6 landing");

        ClientPhysics held = settle(room);
        for (int t = 1; t <= 10; t++) {
            held.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, room);
        }
        assertEquals(0.0, held.y(), 1.0E-12, "held key: still floor-stuck at t10");
        assertTrue(held.onGround());
        held.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, room);
        assertEquals(0.42, held.y(), 1.0E-9, "held key only re-launches on t11");
    }

    @Test
    void latchesRoofEligibilityAcrossTheHop() {
        CollisionView world = threeBlockRoom();
        CritSpam critSpam = new CritSpam();
        BrainMemory mem = new BrainMemory(7L);
        // Grounded probe: gap 1.2 -> eligible, latched.
        Perception grounded = crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L);
        assertEquals(JumpHint.JUMP,
                critSpam.evaluate(grounded, "engage", CRIT_ON, world, mem).jump());
        // Mid-descent at y = 1.0 the LIVE gap reads only 0.2 (below CRIT_GAP_MIN):
        // the grounded verdict must carry the airborne half of the cycle.
        Perception midHop = crafted(1.0, new Vec3d(0.0, -0.155232, 0.0), false, 2.0, 2L);
        CritSpam.Decision d = critSpam.evaluate(midHop, "engage", CRIT_ON, world, mem);
        assertTrue(CritSpam.activeThisTick(mem, 2L), "still active mid-hop");
        assertEquals(JumpHint.NONE, d.jump(), "the pulse is released while airborne");
        assertTrue(d.dropSprint(), "descending: sprint drops for the click window");
    }

    @Test
    void inactiveWhenDisabled() {
        BrainMemory mem = new BrainMemory(7L);
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L),
                "engage", BoxerSettings.DEFAULTS, threeBlockRoom(), mem);
        assertInactive(d, mem, 1L);
    }

    @Test
    void inactiveWhenARoutineOwnsTheTick() {
        // "potHeal" is PotHealGoal's live id; any non-"engage" winner deactivates.
        BrainMemory mem = new BrainMemory(7L);
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L),
                "potHeal", CRIT_ON, threeBlockRoom(), mem);
        assertInactive(d, mem, 1L);
    }

    @Test
    void inactiveDuringTheWtapRelease() {
        BrainMemory mem = new BrainMemory(7L);
        mem.wtapReleaseLeft = 2;
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L),
                "engage", CRIT_ON, threeBlockRoom(), mem);
        assertInactive(d, mem, 1L);
    }

    @Test
    void inactiveOutsideTheMeleeBand() {
        // DEFAULTS reach 3.0 + MELEE_BAND 1.0 = 4.0; a target at 4.5 is out.
        BrainMemory mem = new BrainMemory(7L);
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 4.5, 1L),
                "engage", CRIT_ON, threeBlockRoom(), mem);
        assertInactive(d, mem, 1L);
    }

    @Test
    void inactiveUnderOpenSky() {
        BrainMemory mem = new BrainMemory(7L);
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L),
                "engage", CRIT_ON, FakeWorld.floorAt(0.0), mem);
        assertInactive(d, mem, 1L);
    }

    @Test
    void inactiveBelowTheCpsFloor() {
        // At 2 CPS the metronome fires every 10 ticks -- most hop windows would
        // pass clickless, so the module stays out of the sprint's way entirely.
        BrainMemory mem = new BrainMemory(7L);
        CritSpam.Decision d = new CritSpam().evaluate(
                crafted(0.0, new Vec3d(0.0, -0.0784, 0.0), true, 2.0, 1L),
                "engage", CRIT_ON.withCps(2.0), threeBlockRoom(), mem);
        assertInactive(d, mem, 1L);
    }
}

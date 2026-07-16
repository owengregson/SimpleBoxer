package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.brain.Perception.CombatState;
import me.vexmc.simpleboxer.common.brain.Perception.InventoryView;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TerrainView;
import me.vexmc.simpleboxer.common.brain.Perception.UseItemState;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Pins for the time-to-contact takeoff window. Hand-computed from the integrator
 * constants (launch tick ships {@code v + a + 0.2·sprint} and decays at GROUND
 * drag 0.546; air ticks decay ×0.91 and add {@code 0.98·airAccel}; feet reach
 * 0.42 / 0.7532 / 1.0013 at the ends of air ticks 1–3, so a 1.0 rise clears on
 * tick 3 and the window is {@code (d1+d2, d1+d2+d3]}):
 *
 * <pre>
 * profile   attr  a (ground accel)     post-drag eq v       window (S2, S3]                stride
 * walk      0.10  0.09800000907407407  0.11785904174987763  (0.35331809, 0.49800582]      0.21585905
 * sprint    0.10  0.12740001179629629  0.15321675427484091  (0.76851352, 1.05597956]      0.28061677
 * Speed I   0.12  0.15288001415555552  0.18386010512980910  (0.85528022, 1.17063172]      0.33674012
 * Speed II  0.14  0.17836001651481481  0.21450345598477730  (0.94204693, 1.28528387]      0.39286347
 * </pre>
 *
 * e.g. sprint: v* = a/(1−0.546) = 0.28061677; d1 = v*+0.2 = 0.48061677;
 * d2 = d1·0.546 + 0.98·0.025999999 = 0.28789675; d3 = d2·0.91 + 0.0254800 =
 * 0.28746604. Every pin distance keeps ≥ 0.02 margin from a window edge, so the
 * face probe's ≤ 0.00078 bisection tolerance cannot flip a verdict. At Speed II
 * the stride (0.39286) outruns the window width d3 (0.34324): the trigger must
 * fire one window early when waiting would skip it (crossing on air tick 4 at
 * feet 1.1661 still clears).
 */
class ProactiveJumpTest {

    /** The server's promoted half-width: the box's leading edge sits this far from centre. */
    private static final double HALF = 0.30000001192092896;

    private final ProactiveJump jump = new ProactiveJump();

    private static Perception grounded(double x, double y, double z, Vec3d velocity,
            boolean horizontalCollision, double movementSpeed) {
        SelfState self = new SelfState(x, y, z, velocity, true, horizontalCollision,
                1.0, 1.0, UseItemState.NONE, false, movementSpeed, -1);
        return new Perception(self, null, TerrainView.OPEN, InventoryView.EMPTY,
                CombatState.IDLE, 0);
    }

    /** A grounded eastbound boxer whose box leading edge is {@code faceDistance} short of x=3. */
    private static Perception approaching(double faceDistance, double velocity,
            double movementSpeed) {
        return grounded(3.0 - faceDistance - HALF, 64.0, 0.0,
                new Vec3d(velocity, 0.0, 0.0), false, movementSpeed);
    }

    private static MoveHeading eastward() {
        // +X heading (unit east): non-still, so the probe has a direction to test.
        return new MoveHeading(new Vec3d(1.0, 0.0, 0.0));
    }

    private static BrainMemory mem() {
        return new BrainMemory(1234L);
    }

    /** A one-block step whose face is at x=3, with a broad top for the landing probe. */
    private static FakeWorld stepWorld() {
        return FakeWorld.floorAt(64).wall(3, 64, 0, 6, 64, 0);
    }

    // ---- the window, per speed profile ---------------------------------------

    @Test
    void sprintFiresInsideItsWindow() {
        assertEquals(JumpHint.JUMP, jump.evaluate(
                approaching(0.90, 0.15321675427484091, 0.1), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void sprintHoldsWhenAlreadyTooClose() {
        // 0.70 < S2 = 0.76851: jumping now contacts on air tick 2 with feet at
        // 0.7532 < 1.0 — a face press. Not pinned (no collision yet), so NONE.
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.70, 0.15321675427484091, 0.1), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void sprintWaitsWhileTheWindowIsStillAhead() {
        // 1.30 > S3 = 1.05598, and next tick 1.30 − 0.28062 = 1.01938 is still
        // inside the window — no skip risk, so hold the jump.
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(1.30, 0.15321675427484091, 0.1), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void walkFiresInsideItsWindow() {
        assertEquals(JumpHint.JUMP, jump.evaluate(
                approaching(0.43, 0.11785904174987763, 0.1), eastward(), false, stepWorld(), mem()));
    }

    @Test
    void walkHoldsWhenAlreadyTooClose() {
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.30, 0.11785904174987763, 0.1), eastward(), false, stepWorld(), mem()));
    }

    @Test
    void walkWaitsWhileTheWindowIsStillAhead() {
        // 0.72 > S3 = 0.49801 and 0.72 − 0.21586 = 0.50414 > S2 = 0.35332: no
        // skip risk this tick — the trigger early-fires NEXT tick at 0.504
        // (0.50414 − 0.21586 = 0.28828 ≤ S2), not by entering the window.
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.72, 0.11785904174987763, 0.1), eastward(), false, stepWorld(), mem()));
    }

    @Test
    void speedOneFiresInsideItsWindow() {
        assertEquals(JumpHint.JUMP, jump.evaluate(
                approaching(1.00, 0.18386010512980910, 0.12), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void speedOneHoldsWhenAlreadyTooClose() {
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.80, 0.18386010512980910, 0.12), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void speedTwoFiresInsideItsWindow() {
        assertEquals(JumpHint.JUMP, jump.evaluate(
                approaching(1.10, 0.21450345598477730, 0.14), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void speedTwoHoldsWhenAlreadyTooClose() {
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.90, 0.21450345598477730, 0.14), eastward(), true, stepWorld(), mem()));
    }

    @Test
    void speedTwoFiresEarlyWhenTheStrideWouldSkipTheWindow() {
        // 1.31 > S3 = 1.28528, but 1.31 − stride 0.39286 = 0.91714 ≤ S2 = 0.94205:
        // waiting one tick lands BELOW the window — fire now; the crossing happens
        // on air tick 4 (feet 1.1661 ≥ 1.0), still a clean clear.
        assertEquals(JumpHint.JUMP, jump.evaluate(
                approaching(1.31, 0.21450345598477730, 0.14), eastward(), true, stepWorld(), mem()));
    }

    // ---- vetoes, guards, fallbacks (behavior preserved from the old trigger) --

    @Test
    void doesNotHopAWall() {
        // 3 blocks tall: rise 3.0 > MAX_JUMP_RISE — a detour problem, not a hop.
        CollisionView world = FakeWorld.floorAt(64).wall(3, 64, 0, 3, 66, 0);
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.90, 0.15321675427484091, 0.1), eastward(), true, world, mem()));
    }

    @Test
    void doesNotHopOnFlatGround() {
        assertEquals(JumpHint.NONE, jump.evaluate(
                grounded(0.0, 64.0, 0.0, new Vec3d(0.25, 0.0, 0.0), false, 0.1),
                eastward(), true, FakeWorld.floorAt(64), mem()));
    }

    @Test
    void doesNotHopOntoAThinLipOverAChasm() {
        // A 0.4-thick lip (rise 1.0) at the END of the platform, void beyond and
        // below: the landing probe one block past the face finds no ground within
        // 2 blocks — never launch toward a chasm.
        CollisionView world = FakeWorld.empty()
                .box(new Box(-3.0, 63.0, -1.0, 3.0, 64.0, 2.0))
                .box(new Box(3.0, 64.0, 0.0, 3.4, 65.0, 1.0));
        assertEquals(JumpHint.NONE, jump.evaluate(
                approaching(0.90, 0.15321675427484091, 0.1), eastward(), true, world, mem()));
    }

    @Test
    void respectsCooldownAfterHopping() {
        BrainMemory mem = mem();
        Perception p = approaching(0.90, 0.15321675427484091, 0.1);
        assertEquals(JumpHint.JUMP, jump.evaluate(p, eastward(), true, stepWorld(), mem));
        // Same memory: the countdown must suppress the re-hop.
        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), true, stepWorld(), mem));
    }

    @Test
    void doesNotHopWhileAirborne() {
        SelfState airborne = new SelfState(1.8, 64.5, 0.0, new Vec3d(0.25, 0.0, 0.0),
                false, false, 1.0, 1.0, UseItemState.NONE, false, 0.1, -1);
        Perception p = new Perception(airborne, null, TerrainView.OPEN,
                InventoryView.EMPTY, CombatState.IDLE, 0);
        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), true, stepWorld(), mem()));
    }

    @Test
    void doesNotHopWithoutAHeading() {
        Perception p = grounded(1.8, 64.0, 0.0, Vec3d.ZERO, false, 0.1);
        assertEquals(JumpHint.NONE, jump.evaluate(p, MoveHeading.STILL, true, stepWorld(), mem()));
    }

    @Test
    void hopsWhenPressedAgainstStepAtRest() {
        // Face 0.15 ahead — below every window's floor, but the collision flag +
        // needsJumpAhead fallback still climbs a boxer that stalled into the step.
        Perception p = grounded(3.0 - 0.15 - HALF, 64.0, 0.0, Vec3d.ZERO, true, 0.1);
        assertEquals(JumpHint.JUMP, jump.evaluate(p, eastward(), true, stepWorld(), mem()));
    }

    @Test
    void scheduledRouteTakeoffFiresWithoutAGeometricFace() {
        // No step anywhere (the geometric probe returns null): the route follower's
        // ASCEND cue alone must schedule the takeoff at the same window arithmetic.
        CollisionView world = FakeWorld.floorAt(64);
        BrainMemory mem = mem();
        mem.routeStepFace = 0.90;
        mem.routeStepRise = 1.0;
        assertEquals(JumpHint.JUMP, jump.evaluate(
                grounded(0.0, 64.0, 0.0, new Vec3d(0.15321675427484091, 0.0, 0.0), false, 0.1),
                eastward(), true, world, mem));
    }

    // ---- integrator-in-the-loop: the fired jump actually clears ---------------

    /**
     * Drives the REAL integrator at the trigger's command from a standing start
     * until the boxer stands on the step top. A face-press (horizontal collision
     * on any tick) fails immediately — the pre-fix trigger face-pressed at every
     * sprint speed because it fired below the window.
     */
    private void driveAndClear(double walkSpeed, FakeWorld world) {
        ClientPhysics phys = new ClientPhysics(0.0, 64.0, 0.0);
        phys.setWalkSpeed(walkSpeed);
        BrainMemory mem = mem();
        boolean fired = false;
        for (int tick = 0; tick < 80; tick++) {
            SelfState self = new SelfState(phys.x(), phys.y(), phys.z(), phys.velocity(),
                    phys.onGround(), phys.horizontalCollision(),
                    1.0, 1.0, UseItemState.NONE, false, walkSpeed, -1);
            Perception p = new Perception(self, null, TerrainView.OPEN, InventoryView.EMPTY,
                    CombatState.IDLE, 0);
            JumpHint hint = jump.evaluate(p, eastward(), true, world, mem);
            fired |= hint == JumpHint.JUMP;
            // Yaw −90° faces +X: the sprint-jump push and the forward key drive due East.
            phys.step(new MoveInput(1.0, 0.0, hint == JumpHint.JUMP, true, false), -90.0f, world);
            assertFalse(phys.horizontalCollision(),
                    "the takeoff-window jump must never press the step face (tick " + tick + ")");
            if (phys.onGround() && Math.abs(phys.y() - 65.0) < 1.0E-9) {
                assertTrue(fired, "the climb came from the trigger");
                return; // clean clear: landed on the step top with momentum intact
            }
        }
        throw new AssertionError("never landed on the step top");
    }

    @Test
    void plainSprintClearsAOneBlockStepCleanly() {
        driveAndClear(0.1, FakeWorld.floorAt(64).wall(6, 64, 0, 9, 64, 0));
    }

    @Test
    void speedTwoSprintClearsAOneBlockStepCleanly() {
        // Speed II: the stride outruns the window width, so this also exercises the
        // early-fire path end-to-end.
        driveAndClear(0.14, FakeWorld.floorAt(64).wall(8, 64, 0, 11, 64, 0));
    }
}

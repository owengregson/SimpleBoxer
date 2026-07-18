package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end brain behaviour driven through the REAL {@link ClientPhysics} and a
 * synthetic world — the headline regressions the rework fixes: reaching a target,
 * clearing a one-block step (the momentum jump), circling at a radius band instead
 * of spiralling in, and not staying pinned on a wall.
 */
class BrainTest {

    private static final long SEED = 0xB0EA7L;

    /**
     * The drop-budget cap must FIT the collision-query contract: the deep
     * ground scan issues a column {@code SAFE_DROP_CAP + 1} blocks tall and a
     * block-grid implementation walks one border row past each end, so
     * {@code SAFE_DROP_CAP + 3} rows must survive any implementation's
     * defensive clamp. When this failed (the Bukkit view clamped Y to 8), the
     * scan lost the very surface the boxer stood on: every direction read
     * ledge-ward and the ledge guard froze all movement keys on every version.
     */
    @Test
    void dropBudgetCapFitsTheCollisionQueryContract() {
        assertTrue(Brain.SAFE_DROP_CAP + 3.0 <= CollisionView.DEEP_SCAN_COLUMN_BLOCKS,
                "SAFE_DROP_CAP + 3 must fit DEEP_SCAN_COLUMN_BLOCKS; raising the cap"
                        + " requires raising the contract height with it");
    }

    /** Runs the brain→physics loop for a static target and returns the final physics state. */
    private static ClientPhysics run(BoxerSettings settings, ClientPhysics phys,
            CollisionView world, Vec3d target, int ticks) {
        Brain brain = new Brain(settings, SEED, 0.0f, 0.0f);
        for (int i = 0; i < ticks; i++) {
            Perception p = perceive(phys, target);
            BrainOutput out = brain.tick(p, world, i * 50L);
            phys.step(out.move(), out.aimYaw(), world);
        }
        return phys;
    }

    private static Perception perceive(ClientPhysics phys, Vec3d target) {
        double bx = phys.x();
        double bz = phys.z();
        double dx = target.x() - bx;
        double dz = target.z() - bz;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double bearingToMe = Math.toDegrees(Math.atan2(-(bx - target.x()), (bz - target.z())));
        Perception.SelfState self = new Perception.SelfState(
                bx, phys.y(), bz, phys.velocity(), phys.onGround(), phys.horizontalCollision(),
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState tgt = new Perception.TargetState(
                target.x(), target.y(), target.z(), target.y() + 1.62, Vec3d.ZERO,
                bearingToMe, 0.0, 0.0, distance, false);
        return new Perception(self, tgt, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    private static double horizontalDistance(ClientPhysics phys, Vec3d target) {
        double dx = target.x() - phys.x();
        double dz = target.z() - phys.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void rushesAcrossOpenGroundToTheTarget() {
        FakeWorld world = FakeWorld.floorAt(64);
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        run(BoxerSettings.DEFAULTS, phys, world, new Vec3d(16, 64, 0), 120);
        assertTrue(horizontalDistance(phys, new Vec3d(16, 64, 0)) < 3.0,
                "a rushing boxer closes the gap (was " + horizontalDistance(phys, new Vec3d(16, 64, 0)) + ")");
    }

    @Test
    void clearsAOneBlockStepWithAMomentumJump() {
        // A one-block-high step at x=2 between the boxer and the target: the old
        // reactive "jump when already blocked" never cleared it (velocity zeroed
        // against the face); the proactive jump hops it with momentum intact.
        FakeWorld world = FakeWorld.floorAt(64).block(2, 64, 0).block(2, 64, -1).block(2, 64, 1);
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        run(BoxerSettings.DEFAULTS, phys, world, new Vec3d(6, 65, 0), 160);
        assertTrue(phys.x() > 3.5,
                "the boxer climbed over the one-block step (x=" + phys.x() + ", y=" + phys.y() + ")");
    }

    @Test
    void climbsAStepAcrossALongerApproach() {
        // Mirrors the on-server nav suite: a one-block step at z=5 spanning several
        // blocks, a target 8 out. The boxer must hop the step (not stall on it).
        FakeWorld world = FakeWorld.floorAt(64);
        for (int x = -4; x <= 4; x++) {
            world.block(x, 64, 5);
        }
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        Vec3d target = new Vec3d(0, 64, 8);
        run(BoxerSettings.DEFAULTS, phys, world, target, 200);
        assertTrue(horizontalDistance(phys, target) < 3.0,
                "the boxer hopped the step and reached the target (dist "
                        + horizontalDistance(phys, target) + ", z=" + phys.z() + ", y=" + phys.y() + ")");
    }

    @Test
    void holdsTheMeleePocketWithoutWanderingOff() {
        // A rush boxer already in the pocket makes ~no net progress (it is pressed
        // against where the target stands), which must NOT be mistaken for being
        // stuck and rescued into a sideways detour — it should keep the pocket.
        FakeWorld world = FakeWorld.floorAt(64);
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        Vec3d target = new Vec3d(2, 64, 0);
        run(BoxerSettings.DEFAULTS, phys, world, target, 80);
        assertTrue(horizontalDistance(phys, target) < 2.0,
                "the boxer held the pocket rather than sidestepping (dist "
                        + horizontalDistance(phys, target) + ")");
    }

    @Test
    void circleStrafeHoldsARadiusBandInsteadOfSpirallingIn() {
        FakeWorld world = FakeWorld.floorAt(64);
        BoxerSettings settings = BoxerSettings.DEFAULTS.withMovement(
                new BoxerSettings.Movement(BoxerSettings.Movement.Style.STRAFE_CIRCLE, 0.0, true));
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        Vec3d target = new Vec3d(8, 64, 0);
        Brain brain = new Brain(settings, SEED, 0.0f, 0.0f);
        double minDist = Double.MAX_VALUE;
        double maxAbsZ = 0.0;
        for (int i = 0; i < 220; i++) {
            Perception p = perceive(phys, target);
            BrainOutput out = brain.tick(p, world, i * 50L);
            phys.step(out.move(), out.aimYaw(), world);
            if (i > 70) { // after it has closed and started orbiting
                minDist = Math.min(minDist, horizontalDistance(phys, target));
                maxAbsZ = Math.max(maxAbsZ, Math.abs(phys.z()));
            }
        }
        double finalDist = horizontalDistance(phys, target);
        assertTrue(minDist > 1.0,
                "the orbit never collapses onto the target (min dist " + minDist + ")");
        assertTrue(finalDist > 1.0 && finalDist < 5.0,
                "the orbit holds a radius band (final dist " + finalDist + ")");
        assertTrue(maxAbsZ > 1.0,
                "the boxer actually circled off the approach axis (max |z|=" + maxAbsZ + ")");
    }

    @Test
    void doesNotStayPinnedAgainstATallWall() {
        // A tall wall (2 blocks) directly between boxer and target: it cannot be
        // jumped, so the boxer must slide along / reroute, not stall at the face.
        FakeWorld world = FakeWorld.floorAt(64);
        for (int z = -4; z <= 4; z++) {
            if (z == -4 || z == 4) {
                continue; // leave a way around the ends
            }
            world.block(3, 64, z).block(3, 65, z);
        }
        ClientPhysics phys = new ClientPhysics(0, 64, 0);
        Vec3d target = new Vec3d(8, 64, 0);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        double maxX = phys.x();
        double maxAbsZ = 0.0;
        for (int i = 0; i < 260; i++) {
            Perception p = perceive(phys, target);
            BrainOutput out = brain.tick(p, world, i * 50L);
            phys.step(out.move(), out.aimYaw(), world);
            maxX = Math.max(maxX, phys.x());
            maxAbsZ = Math.max(maxAbsZ, Math.abs(phys.z()));
        }
        // A frozen boxer sits at x≈2.4, z≈0 forever. A navigating one either gets
        // PAST the 2-tall wall (which requires going around it) or at least works
        // well out to an opening — never just grinds the face.
        assertTrue(maxX > 4.0 || maxAbsZ > 3.5,
                "the boxer worked around the wall instead of freezing (maxX="
                        + maxX + ", maxAbsZ=" + maxAbsZ + ", final x=" + phys.x() + " z=" + phys.z() + ")");
    }

    /** The platform-and-off-line-stairs arena shared by the elevation tests: target
     *  platform top 67 over cells x8..10 z0..1, staircase around the corner at x=11. */
    private static FakeWorld elevatedArena() {
        return FakeWorld.floorAt(64)
                .wall(8, 66, 0, 10, 66, 1)
                .block(11, 64, 3)                                    // top 65
                .block(11, 64, 2).block(11, 65, 2)                   // top 66
                .block(11, 64, 1).block(11, 65, 1).block(11, 66, 1); // top 67
    }

    /**
     * THE headline regression: a target on a raised platform whose only access is
     * an off-line staircase ~11 cells away, behind a corner. This is the one test
     * that would have caught the walk-only short-circuit, the horizontal-only gate
     * shutoff, the 10-cell search horizon, AND the follower's route-dropping —
     * together. The boxer's own feet must reach the platform level.
     */
    @Test
    void climbsOffLineStairsToAnElevatedTarget() {
        FakeWorld world = elevatedArena();
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Vec3d target = new Vec3d(9.5, 67, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        double topY = 64.0;
        for (int i = 0; i < 600 && topY < 67.0; i++) {
            Perception p = perceive(phys, target);
            BrainOutput out = brain.tick(p, world, i * 50L);
            phys.step(out.move(), out.aimYaw(), world);
            topY = Math.max(topY, phys.y());
        }
        assertTrue(topY >= 67.0,
                "the boxer's feet must reach the platform level (got " + topY + ")");
    }

    /**
     * Follower resilience: a 1-cell strafe by the platform player must NOT dissolve
     * a committed climb — the latch holds the route, and the replacement search is
     * throttled so the swap attempt cannot thrash. (The route mints a few ticks in
     * now: the elevation gate needs its 10-tick gap persistence and the sliced
     * search a few 50-expansion ticks.)
     */
    @Test
    void committedClimbSurvivesTargetCellChanges() {
        FakeWorld world = elevatedArena();
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);

        int minted = -1;
        for (int i = 0; i < 120 && minted < 0; i++) {
            brain.tick(perceive(phys, new Vec3d(9.5, 67, 0.5)), world, i * 50L);
            if (brain.memory().path != null) {
                minted = i;
            }
        }
        assertTrue(minted >= 0, "an elevated target must mint a climb route");
        assertTrue(brain.memory().climbLatch, "and arm the climb latch");

        brain.tick(perceive(phys, new Vec3d(8.5, 67, 1.5)), world, (minted + 1) * 50L);
        assertTrue(brain.memory().path != null, "a 1-cell strafe must not dissolve the climb");
        assertTrue(brain.memory().climbLatch, "the latch holds until the level is reached");
    }

    /**
     * Requirement (4) end-to-end: a 3×3 pillar (top 69) over the floor (64),
     * target 6 out. Default traits (max health 20 → budget 10 points → 13 safe
     * blocks; the 5-drop costs ceil(5−3) = 2) let the corridor probe validate
     * the line — the boxer walks off at pace and closes. No plan is ever needed.
     */
    @Test
    void dropsOffALedgeToATargetBelow() {
        FakeWorld world = FakeWorld.floorAt(64).wall(-1, 64, -1, 1, 68, 1);
        ClientPhysics phys = new ClientPhysics(0.5, 69, 0.5);
        Vec3d target = new Vec3d(6.5, 64, 0.5);
        run(BoxerSettings.DEFAULTS, phys, world, target, 200);
        assertTrue(phys.y() < 64.5, "the boxer took the drop (y=" + phys.y() + ")");
        assertTrue(horizontalDistance(phys, target) < 3.0,
                "and closed on the target below (dist " + horizontalDistance(phys, target) + ")");
    }

    /**
     * Requirement (2): while a sliced search grinds against an UNREACHABLE
     * floating platform (space-exhausting flood, adopted by nobody — the
     * Y-progress gate refuses its partials), the boxer must keep moving: on at
     * least 40% of the search-active ticks the motor holds a movement key (the
     * bar sits below the duty cycle on purpose, so ticks spent in
     * POOR_CANDIDATE_SPEED slides against the platform face cannot flake it).
     */
    @Test
    void keepsSteeringWhileASlicedSearchRuns() {
        FakeWorld world = FakeWorld.floorAt(64).wall(8, 65, -1, 10, 67, 1); // top 68, sheer
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        Vec3d target = new Vec3d(9.5, 68, 0.5);
        int searchTicks = 0;
        int movingSearchTicks = 0;
        for (int i = 0; i < 120; i++) {
            BrainOutput out = brain.tick(perceive(phys, target), world, i * 50L);
            if (brain.memory().search != null) {
                searchTicks++;
                if (out.move().forward() != 0.0 || out.move().strafe() != 0.0) {
                    movingSearchTicks++;
                }
            }
            phys.step(out.move(), out.aimYaw(), world);
        }
        assertTrue(searchTicks >= 10,
                "the flooding search must span many ticks (got " + searchTicks + ")");
        assertTrue(movingSearchTicks * 5 >= searchTicks * 2,
                "steering keeps driving while the search flies ("
                        + movingSearchTicks + "/" + searchTicks + ")");
    }

    /**
     * Requirement (3): a simulated combo (hits landing every 15 ticks) against a
     * platform target 4 blocks away — inside the hold band with a persistent
     * 3-block gap that would otherwise open the elevation gate — creates NO plan
     * state whatsoever for the whole exchange; once the window lapses the search
     * resumes. The boxer is held static so the anti-stuck rescue path is armed
     * too (it must be equally excluded).
     */
    @Test
    void combatHoldSuppressesAllPlanningDuringACombo() {
        FakeWorld world = elevatedArena();
        ClientPhysics phys = new ClientPhysics(5.5, 64, 0.5); // 4.0 horizontal from the target
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        Vec3d target = new Vec3d(9.5, 67, 0.5);
        for (int i = 0; i < 70; i++) {
            if (i % 15 == 0) {
                brain.onHitLanded(); // the combo keeps re-stamping the window
            }
            brain.tick(perceive(phys, target), world, i * 50L);
            assertTrue(brain.memory().search == null,
                    "no search may open or step mid-combo (tick " + i + ")");
            assertTrue(brain.memory().path == null,
                    "no route may mint mid-combo (tick " + i + ")");
        }
        int resumed = -1;
        for (int i = 70; i < 200 && resumed < 0; i++) {
            brain.tick(perceive(phys, target), world, i * 50L);
            if (brain.memory().search != null || brain.memory().path != null) {
                resumed = i;
            }
        }
        assertTrue(resumed >= 0, "once the hold lapses the elevation search resumes");
    }

    /**
     * The dy-persistence hysteresis: 9-tick bursts of elevation gap (with a
     * level-target tick between bursts) never open the gate; the 10th
     * CONSECUTIVE tick does. The boxer walks (physics stepped) so anti-stuck
     * stays quiet, and the platform sits far enough out (25 cells) that the
     * corridor horizon can't preempt the gate.
     */
    @Test
    void elevationGateNeedsAPersistentGap() {
        FakeWorld world = FakeWorld.floorAt(64).wall(24, 65, -1, 26, 66, 1); // slab top 67
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        Vec3d raised = new Vec3d(25.5, 67, 0.5);
        Vec3d level = new Vec3d(10.5, 64, 0.5);
        int tick = 0;
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int k = 0; k < 9; k++) {
                BrainOutput out = brain.tick(perceive(phys, raised), world, tick++ * 50L);
                phys.step(out.move(), out.aimYaw(), world);
                assertTrue(brain.memory().search == null && brain.memory().path == null,
                        "9 consecutive gap ticks must never open the gate (tick " + tick + ")");
            }
            BrainOutput out = brain.tick(perceive(phys, level), world, tick++ * 50L);
            phys.step(out.move(), out.aimYaw(), world); // the transient ends — counter resets
        }
        for (int k = 0; k < Brain.ELEVATION_GAP_PERSIST_TICKS; k++) {
            BrainOutput out = brain.tick(perceive(phys, raised), world, tick++ * 50L);
            phys.step(out.move(), out.aimYaw(), world);
        }
        assertTrue(brain.memory().search != null,
                "the 10th consecutive gap tick opens the gate");
    }

    /**
     * Stall report A4's fix: the w-tap release window (EngageGoal returns a zero
     * desired) must HOLD a committed route — every landed hit used to wipe it.
     */
    @Test
    void wtapReleaseWindowHoldsTheCommittedRoute() {
        FakeWorld world = elevatedArena();
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);
        Vec3d target = new Vec3d(9.5, 67, 0.5);
        int minted = -1;
        for (int i = 0; i < 120 && minted < 0; i++) {
            brain.tick(perceive(phys, target), world, i * 50L);
            if (brain.memory().path != null) {
                minted = i;
            }
        }
        assertTrue(minted >= 0, "a climb route must mint first");
        int cursor = brain.memory().pathCursor;

        brain.memory().wtapReleaseLeft = 2; // simulate a landed hit's release window
        brain.tick(perceive(phys, target), world, (minted + 1) * 50L);
        brain.tick(perceive(phys, target), world, (minted + 2) * 50L);
        assertTrue(brain.memory().path != null,
                "the release window must HOLD the route, not wipe it");
        assertEquals(cursor, brain.memory().pathCursor, "exactly where it left off");
    }
}

package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end brain behaviour driven through the REAL {@link ClientPhysics} and a
 * synthetic world — the headline regressions the rework fixes: reaching a target,
 * clearing a one-block step (the momentum jump), circling at a radius band instead
 * of spiralling in, and not staying pinned on a wall.
 */
class BrainTest {

    private static final long SEED = 0xB0EA7L;

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
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1);
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
     * a committed climb — the latch holds the route and the replan throttle keeps
     * the swap attempt from thrashing.
     */
    @Test
    void committedClimbSurvivesTargetCellChanges() {
        FakeWorld world = elevatedArena();
        ClientPhysics phys = new ClientPhysics(0.5, 64, 0.5);
        Brain brain = new Brain(BoxerSettings.DEFAULTS, SEED, 0.0f, 0.0f);

        brain.tick(perceive(phys, new Vec3d(9.5, 67, 0.5)), world, 0L);
        assertTrue(brain.memory().path != null, "an elevated target must mint a climb route");
        assertTrue(brain.memory().climbLatch, "and arm the climb latch");

        brain.tick(perceive(phys, new Vec3d(8.5, 67, 1.5)), world, 50L);
        assertTrue(brain.memory().path != null, "a 1-cell strafe must not dissolve the climb");
        assertTrue(brain.memory().climbLatch, "the latch holds until the level is reached");
    }
}

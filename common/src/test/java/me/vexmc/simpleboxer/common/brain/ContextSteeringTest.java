package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for the context-steering kernel: full-speed toward the goal on
 * open ground, an along-the-wall slide when the goal points into a wall, and a
 * still heading when there is no desire to move.
 */
class ContextSteeringTest {

    private static final Vec3d EAST = new Vec3d(1.0, 0.0, 0.0);

    private final ContextSteering steering = new ContextSteering();

    /** A boxer standing at world (x,y,z) with the given horizontal velocity. */
    private static Perception perceptionAt(double x, double y, double z, Vec3d velocity) {
        Perception.SelfState self = new Perception.SelfState(
                x, y, z, velocity, true, false, 1.0, 1.0,
                Perception.UseItemState.NONE, false);
        return new Perception(self, null, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    @Test
    void openFloor_desiredEast_headingStaysEast() {
        FakeWorld world = FakeWorld.floorAt(64);
        Perception p = perceptionAt(0.5, 64, 0.5, Vec3d.ZERO);

        MoveHeading heading = steering.steer(p, EAST, world);

        assertFalse(heading.isStill(), "should produce a real heading on open ground");
        double dotEast = heading.dirWorld().normalized().dot(EAST);
        assertTrue(dotEast > 0.99, "unobstructed heading should match desired East, got dot=" + dotEast);
        assertEquals(1.0, heading.speedScale(), 1.0E-9, "clear ground should run at full speed");
        assertFalse(heading.nearLedge(), "flat infinite floor has no ledge");
    }

    @Test
    void wallDueEast_desiredEast_deflectsToSlideAlongWall() {
        // A tall (4-block) broad wall directly East of the boxer — unjumpable.
        FakeWorld world = FakeWorld.floorAt(64).wall(1, 64, -2, 1, 67, 3);
        Perception p = perceptionAt(0.5, 64, 0.5, EAST.scale(0.2)); // pressing east

        MoveHeading heading = steering.steer(p, EAST, world);

        assertFalse(heading.isStill(), "boxer should keep moving, just not into the wall");

        // It deflects: no longer pointing straight into the wall normal.
        double dotNormal = heading.dirWorld().normalized().dot(EAST);
        assertTrue(dotNormal < 0.99,
                "heading should deflect off the wall normal, got dot=" + dotNormal);

        // And the chosen heading is actually clear — it slides along, not into.
        Box box = NavGeometry.playerBox(0.5, 64, 0.5);
        assertFalse(NavGeometry.wallAhead(world, box, heading.dirWorld(), NavGeometry.LOOK_AHEAD),
                "the chosen slide heading must not run into the wall");
    }

    @Test
    void pursuitWalksOffLedgeTowardTarget() {
        // A raised platform (top y=64) covering x<=1, with a sheer drop to the East
        // (a void beyond x=1). The target sits past the edge, so the goal wants East.
        FakeWorld world = FakeWorld.empty().wall(-5, 63, -5, 0, 63, 5);
        // Perched at the eastern rim, already pressing east at a sprint.
        Perception p = perceptionAt(0.9, 64, 0.5, EAST.scale(0.3));

        // A survival/default goal (mayLeaveLedges == false) refuses the edge: it
        // deflects to pace the rim rather than stepping off toward the target.
        MoveHeading paces = steering.steer(p, EAST, world, false);
        assertTrue(paces.dirWorld().normalized().dot(EAST) < 0.99,
                "a ledge-averse boxer should deflect off the rim, got dot="
                        + paces.dirWorld().normalized().dot(EAST));

        // A pursuing goal (mayLeaveLedges == true) walks straight off the edge
        // toward the target, exactly like a real client chasing an opponent.
        MoveHeading chases = steering.steer(p, EAST, world, true);
        assertFalse(chases.isStill(), "pursuit should keep moving toward the target");
        double dotEast = chases.dirWorld().normalized().dot(EAST);
        assertTrue(dotEast > 0.99,
                "pursuit should advance off the ledge toward the target, got dot=" + dotEast);
    }

    @Test
    void obliqueWallKeepsNearestDesiredHeading() {
        // A tall wall due East (x in [1,2]); the goal wants to travel OBLIQUELY into
        // it (40deg north of East). A real client presses that heading and physics
        // slides it north along the wall — it does NOT turn 90deg to walk due North.
        FakeWorld world = FakeWorld.floorAt(64).wall(1, 64, -10, 1, 67, 10);
        Vec3d desired = new Vec3d(0.76604, 0.0, 0.64279); // 40deg north of East, unit
        Perception p = perceptionAt(0.5, 64, 0.5, desired.scale(0.3)); // pressing at a sprint

        MoveHeading heading = steering.steer(p, desired, world);
        assertFalse(heading.isStill(), "should keep pressing toward the target");

        // The chosen heading hugs the desired direction (a shallow graze physics
        // will finish) rather than snapping to the pure along-wall perpendicular
        // (due North, dot with desired ~= 0.64).
        double dotDesired = heading.dirWorld().normalized().dot(desired.normalized());
        assertTrue(dotDesired > 0.9,
                "oblique slide should keep the nearest-to-desired heading, got dot=" + dotDesired);

        // It is genuinely a graze: the heading still runs into the wall within the
        // full look-ahead — ClientPhysics.collide finishes the slide, we don't.
        Box box = NavGeometry.playerBox(0.5, 64, 0.5);
        assertTrue(NavGeometry.wallAhead(world, box, heading.dirWorld(), NavGeometry.LOOK_AHEAD),
                "the kept heading should graze the wall, not fully clear it");
    }

    @Test
    void desiredZero_returnsStill() {
        FakeWorld world = FakeWorld.floorAt(64);
        Perception p = perceptionAt(0.5, 64, 0.5, Vec3d.ZERO);

        MoveHeading heading = steering.steer(p, Vec3d.ZERO, world);

        assertTrue(heading.isStill(), "no desired direction should hold position");
        assertEquals(MoveHeading.STILL, heading);
    }
}

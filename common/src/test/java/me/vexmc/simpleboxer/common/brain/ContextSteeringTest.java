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
                Perception.UseItemState.NONE, false, 0.1, -1, 20.0, 0, 3.0, 1.0, false);
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
    void pursuitWalksOffALedgeWithinItsDropBudget() {
        // A platform (top 64) with a landing floor 5 below (top 59) past the rim.
        // At the no-gear budget (13) the 5-drop has ground within budget — not a
        // ledge at all: the pursuit heading stays dead East at full pace, and no
        // sneak ease-off fires. The DEFAULT budget (3.0) still deflects.
        FakeWorld world = FakeWorld.empty()
                .wall(-5, 63, -5, 0, 63, 5)   // platform, top 64
                .wall(1, 58, -5, 8, 58, 5);   // landing, top 59 (5 below)
        Perception p = perceptionAt(0.9, 64, 0.5, EAST.scale(0.3));

        MoveHeading chases = steering.steer(p, EAST, world, 13.0);
        assertFalse(chases.isStill(), "pursuit should keep moving toward the target");
        assertTrue(chases.dirWorld().normalized().dot(EAST) > 0.99,
                "a within-budget drop is a deliberate descent, got dot="
                        + chases.dirWorld().normalized().dot(EAST));
        assertFalse(chases.nearLedge(), "no sneak ease-off on a budgeted drop");

        MoveHeading paces = steering.steer(p, EAST, world); // the 3.0 default
        assertTrue(paces.dirWorld().normalized().dot(EAST) < 0.99,
                "the ledge-averse default still refuses the 5-drop, got dot="
                        + paces.dirWorld().normalized().dot(EAST));
    }

    @Test
    void pursuitRefusesALedgeBeyondItsDropBudget() {
        // The same rim over VOID — deeper than any budget. Pursuit deflects too:
        // the old mayLeaveLedges=true walked off 23-block cliffs for free.
        FakeWorld world = FakeWorld.empty().wall(-5, 63, -5, 0, 63, 5);
        Perception p = perceptionAt(0.9, 64, 0.5, EAST.scale(0.3));
        MoveHeading paces = steering.steer(p, EAST, world, 13.0);
        assertTrue(paces.dirWorld().normalized().dot(EAST) < 0.99,
                "a bottomless edge deflects even pursuit, got dot="
                        + paces.dirWorld().normalized().dot(EAST));
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

    // --- 0.7.0: lateral clearance (berth) -------------------------------------

    /** Like {@link #perceptionAt} but with a live target {@code dist} blocks east. */
    private static Perception perceptionWithTarget(double x, double y, double z, Vec3d velocity,
            double targetX, double targetZ) {
        Perception.SelfState self = new Perception.SelfState(
                x, y, z, velocity, true, false, 1.0, 1.0,
                Perception.UseItemState.NONE, false, 0.1, -1, 20.0, 0, 3.0, 1.0, false);
        double dx = targetX - x;
        double dz = targetZ - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        Perception.TargetState target = new Perception.TargetState(
                targetX, y, targetZ, y + 1.62, Vec3d.ZERO, 0.0, 0.0, 0.0, dist, false);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY, Perception.CombatState.IDLE, 0);
    }

    @Test
    void parallelWallBowsTheHeadingOut() {
        // Hugging a 2-tall wall at a 0.5 gap, desired parallel: E scores 1−0.45,
        // the 45° bow-out cos45−0.1 = 0.607 — the bow-out wins (see the lateral
        // penalty javadoc for the arithmetic). It bows AWAY from the wall (south)
        // and keeps real progress, at full speed (berth never throttles).
        FakeWorld world = FakeWorld.floorAt(64).wall(-5, 64, 1, 5, 65, 1);
        Perception p = perceptionAt(0.5, 64, 0.5, EAST.scale(0.28));

        MoveHeading heading = steering.steer(p, EAST, world);

        double dot = heading.dirWorld().normalized().dot(EAST);
        assertTrue(dot < 0.75, "the hug lane must deflect (got dot=" + dot + ")");
        assertTrue(dot > 0.5, "but keep making progress (got dot=" + dot + ")");
        assertTrue(heading.dirWorld().z() < 0.0, "bows AWAY from the wall (south)");
        assertEquals(1.0, heading.speedScale(), 1.0E-9, "lateral shaping must not throttle");
    }

    @Test
    void wellClearOfTheWallHoldsTheLine() {
        // At a 2.5-block gap both lateral bands are clear — travel re-parallels.
        FakeWorld world = FakeWorld.floorAt(64).wall(-5, 64, 1, 5, 65, 1);
        Perception p = perceptionAt(0.5, 64, -1.5, EAST.scale(0.28));

        MoveHeading heading = steering.steer(p, EAST, world);

        assertTrue(heading.dirWorld().normalized().dot(EAST) > 0.99,
                "clear of the berth band the line holds");
    }

    @Test
    void corridorRunsStraightAtFullSpeed() {
        // Both sides mark every open candidate (0.45 × 2); the normalization floor
        // removes it, so the corridor line scores 1.0 and never slows.
        FakeWorld world = FakeWorld.floorAt(64)
                .wall(-5, 64, 1, 5, 65, 1)
                .wall(-5, 64, -1, 5, 65, -1);
        Perception p = perceptionAt(0.5, 64, 0.5, EAST.scale(0.28));

        MoveHeading heading = steering.steer(p, EAST, world);

        assertTrue(heading.dirWorld().normalized().dot(EAST) > 0.99, "corridor: hold the line");
        assertEquals(1.0, heading.speedScale(), 1.0E-9, "normalized lateral must not throttle");
    }

    @Test
    void meleePocketKeepsRangeDisciplineOverBerth() {
        // A target 3 blocks out (inside the 4.0 exemption): berth switches off —
        // orbiting a cornered opponent must still hug the wall.
        FakeWorld world = FakeWorld.floorAt(64).wall(-5, 64, 1, 5, 65, 1);
        Perception p = perceptionWithTarget(0.5, 64, 0.5, EAST.scale(0.28), 3.5, 0.5);

        MoveHeading heading = steering.steer(p, EAST, world);

        assertTrue(heading.dirWorld().normalized().dot(EAST) > 0.99,
                "inside the pocket the hug is correct (range discipline)");
    }
}

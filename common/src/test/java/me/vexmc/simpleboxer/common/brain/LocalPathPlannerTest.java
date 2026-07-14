package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Exercises the bounded A* over synthetic {@link FakeWorld} geometry. The floor
 * surface sits at y = 64, so a player's feet rest at 64 and returned waypoints
 * carry that floor Y; pillars/walls are two blocks tall so they cannot be hopped
 * (a full-block pillar would otherwise read as a jumpable STEP).
 */
class LocalPathPlannerTest {

    private final LocalPathPlanner planner = new LocalPathPlanner();

    @Test
    void openFloorRoutesRoughlyStraight() {
        FakeWorld world = FakeWorld.floorAt(64);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 500);

        assertTrue(route.isPresent(), "an open floor must yield a route");
        List<Vec3d> path = route.get();
        assertFalse(path.isEmpty(), "route should have at least the goal waypoint");

        // Straight along +X: every waypoint keeps the starting Z lane (cell z=0 -> 0.5).
        for (Vec3d w : path) {
            assertEquals(0.5, w.z(), 1.0E-9, "straight route must not leave the z=0 lane");
            assertEquals(64.0, w.y(), 1.0E-9, "waypoint floor Y is the block top (64)");
        }
        Vec3d last = path.get(path.size() - 1);
        assertEquals(6.5, last.x(), 1.0E-9, "final waypoint is the goal cell centre");
    }

    @Test
    void routesAroundATwoTallWall() {
        // A two-tall wall (y 64..66) at x=3 spanning z cells -1,0,1 blocks the straight
        // shot from (0,0) to (6,0); the only way through is to detour in z.
        FakeWorld world = FakeWorld.floorAt(64).wall(3, 64, -1, 3, 65, 1);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 500);

        assertTrue(route.isPresent(), "a route around the wall must exist");
        List<Vec3d> path = route.get();

        boolean detoured = path.stream().anyMatch(w -> Math.abs(w.z() - 0.5) > 0.4);
        assertTrue(detoured, "route must leave the z=0 lane to get around the wall");

        // No waypoint may land inside the wall footprint (x cell 3, z cell in {-1,0,1}).
        for (Vec3d w : path) {
            boolean insideWall = Math.floor(w.x()) == 3
                    && Math.floor(w.z()) >= -1 && Math.floor(w.z()) <= 1;
            assertFalse(insideWall, "no waypoint may sit inside the wall: " + w);
        }
        Vec3d last = path.get(path.size() - 1);
        assertEquals(6.5, last.x(), 1.0E-9);
        assertEquals(0.5, last.z(), 1.0E-9);
    }

    @Test
    void walledInStartHasNoRoute() {
        // Ring the start cell (0,0) with two-tall walls on all 8 neighbours: no edge
        // can leave, so the search exhausts within budget and returns empty.
        FakeWorld world = FakeWorld.floorAt(64)
                .wall(-1, 64, -1, -1, 65, 1) // west column (cells (-1,-1..1))
                .wall(1, 64, -1, 1, 65, 1)   // east column (cells (1,-1..1))
                .wall(0, 64, -1, 0, 65, -1)  // north cell (0,-1)
                .wall(0, 64, 1, 0, 65, 1);   // south cell (0,1)

        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 500);
        assertTrue(route.isEmpty(), "a fully walled-in start cannot reach the goal");
    }

    @Test
    void budgetExhaustionReturnsEmpty() {
        // A reachable goal but essentially zero budget: the first expansion overruns.
        FakeWorld world = FakeWorld.floorAt(64);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 0);
        assertTrue(route.isEmpty(), "no expansions allowed -> no route");
    }

    @Test
    void goalOnStartCellReturnsSingleWaypoint() {
        FakeWorld world = FakeWorld.floorAt(64);
        var route = planner.route(new Vec3d(0.2, 65, 0.2), new Vec3d(0.8, 65, 0.8), world, 500);

        assertTrue(route.isPresent());
        List<Vec3d> path = route.get();
        assertEquals(1, path.size(), "same-cell goal collapses to one waypoint");
        assertEquals(0.5, path.get(0).x(), 1.0E-9);
        assertEquals(0.5, path.get(0).z(), 1.0E-9);
    }

    @Test
    void fullBlockPillarIsJumpedNotRoutedAround() {
        // A single-block pillar (top at y=65) is a jumpable STEP/JUMP, so the planner
        // may pass straight over it rather than detour — it still returns a route.
        FakeWorld world = FakeWorld.floorAt(64).block(3, 64, 0);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 500);
        assertTrue(route.isPresent(), "a one-block pillar must not defeat the planner");
    }
}

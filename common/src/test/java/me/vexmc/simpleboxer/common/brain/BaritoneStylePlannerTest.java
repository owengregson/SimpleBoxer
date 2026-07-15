package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Exercises the 3D voxel A* over synthetic {@link FakeWorld} geometry — the same style
 * {@link LocalPathPlannerTest} uses, so the upgraded planner is tested against the exact
 * geometry the integrator would collide with. Floors are placed so a cell's feet rest on the
 * block top (a floor top at y = 64 → feet/waypoint Y = 64). Walls/pillars are two blocks tall
 * unless a single-block step is the point of the test.
 */
class BaritoneStylePlannerTest {

    private final BaritoneStylePlanner planner = new BaritoneStylePlanner();

    // --- flat / horizontal --------------------------------------------------

    @Test
    void flatOpenFloorSprintsStraight() {
        FakeWorld world = FakeWorld.floorAt(64);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 500, true);

        assertTrue(route.isPresent(), "an open floor must yield a route");
        List<Vec3d> path = route.get();
        assertFalse(path.isEmpty(), "route should have at least the goal waypoint");
        for (Vec3d w : path) {
            assertEquals(0.5, w.z(), 1.0E-9, "straight route stays in the z=0 lane");
            assertEquals(64.0, w.y(), 1.0E-9, "waypoint floor Y is the block top (64)");
        }
        assertEquals(6.5, path.get(path.size() - 1).x(), 1.0E-9, "final waypoint is the goal cell centre");
    }

    @Test
    void routesAroundATwoTallWall() {
        FakeWorld world = FakeWorld.floorAt(64).wall(3, 64, -1, 3, 65, 1);
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 1000, true);

        assertTrue(route.isPresent(), "a route around the wall must exist");
        List<Vec3d> path = route.get();
        assertTrue(path.stream().anyMatch(w -> Math.abs(w.z() - 0.5) > 0.4),
                "route must leave the z=0 lane to get around the wall");
        for (Vec3d w : path) {
            boolean insideWall = Math.floor(w.x()) == 3 && Math.floor(w.z()) >= -1 && Math.floor(w.z()) <= 1;
            assertFalse(insideWall, "no waypoint may sit inside the wall: " + w);
        }
        Vec3d last = path.get(path.size() - 1);
        assertEquals(6.5, last.x(), 1.0E-9);
        assertEquals(0.5, last.z(), 1.0E-9);
    }

    @Test
    void escapesAConcaveUTrap() {
        // A pocket around the start, open only to the WEST; the goal is to the EAST. A greedy
        // reactive controller grinds into the east wall; the planner must back out west and loop.
        FakeWorld world = FakeWorld.floorAt(64)
                .wall(1, 64, -1, 1, 65, 1)   // east wall (cells (1,-1..1))
                .wall(-1, 64, 1, 1, 65, 1)   // north wall (cells (-1..1, 1))
                .wall(-1, 64, -1, 1, 65, -1); // south wall (cells (-1..1, -1))
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(5, 65, 0), world, 3000, true);

        assertTrue(route.isPresent(), "the planner must escape the U-trap");
        List<Vec3d> path = route.get();
        assertTrue(path.stream().anyMatch(w -> w.x() < 0.0),
                "escaping means first heading WEST (away from the goal), out of the pocket");
        Vec3d last = path.get(path.size() - 1);
        assertEquals(5.5, last.x(), 1.0E-9);
        assertEquals(0.5, last.z(), 1.0E-9);
    }

    // --- verticality --------------------------------------------------------

    @Test
    void ascendsASingleStepUpWhenJumpsAllowed() {
        // A full-width one-block step at x=3 (top 65): the only way east is up and over.
        FakeWorld world = FakeWorld.floorAt(64).wall(3, 64, -10, 3, 64, 10);

        var climbed = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 6000, true);
        assertTrue(climbed.isPresent(), "with jumps allowed the boxer climbs the step");
        List<Vec3d> up = climbed.get();
        assertTrue(up.stream().anyMatch(w -> w.y() == 65.0), "a waypoint must sit on top of the step (y=65)");
        assertEquals(6.5, up.get(up.size() - 1).x(), 1.0E-9, "and it reaches the far side");

        var walk = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 6000, false);
        // Jumps banned + the step spans the whole box ⇒ it cannot cross; any partial stays west of it.
        walk.ifPresent(p -> assertTrue(p.stream().allMatch(w -> w.x() < 3.0),
                "a jumpless boxer cannot cross a full-width step-up"));
    }

    @Test
    void descendsAStaircaseToALowerGoal() {
        // A 1-wide descending staircase in the void: 63 → 62 → 61 → 60, so the only route is down it.
        FakeWorld world = FakeWorld.empty()
                .block(0, 60, 0).block(0, 61, 0).block(0, 62, 0) // top 63 (start)
                .block(1, 60, 0).block(1, 61, 0)                 // top 62
                .block(2, 60, 0)                                 // top 61
                .block(3, 59, 0).block(4, 59, 0).block(5, 59, 0).block(6, 59, 0); // top 60
        var route = planner.route(new Vec3d(0.5, 64, 0.5), new Vec3d(6, 61, 0), world, 2000, true);

        assertTrue(route.isPresent(), "the staircase down must be routable");
        List<Vec3d> path = route.get();
        double prevY = 64.0;
        for (Vec3d w : path) {
            assertTrue(w.y() <= prevY + 1.0E-9, "the staircase only ever steps DOWN: " + w);
            prevY = w.y();
        }
        assertTrue(path.stream().anyMatch(w -> w.y() == 62.0), "passes the 62 step");
        assertTrue(path.stream().anyMatch(w -> w.y() == 61.0), "passes the 61 step");
        Vec3d last = path.get(path.size() - 1);
        assertEquals(6.5, last.x(), 1.0E-9);
        assertEquals(60.0, last.y(), 1.0E-9);
    }

    @Test
    void fallsIntoAPitRatherThanStepping() {
        // A platform (top 63) that drops straight to a pit floor (top 60) — a 3-block FALL, no stairs.
        FakeWorld world = FakeWorld.empty()
                .block(0, 60, 0).block(0, 61, 0).block(0, 62, 0) // top 63 (start)
                .block(1, 60, 0).block(1, 61, 0).block(1, 62, 0) // top 63
                .block(2, 59, 0).block(3, 59, 0).block(4, 59, 0); // pit floor top 60
        var route = planner.route(new Vec3d(0.5, 64, 0.5), new Vec3d(4, 61, 0), world, 2000, true);

        assertTrue(route.isPresent(), "the boxer must be able to drop into the pit");
        List<Vec3d> path = route.get();
        for (Vec3d w : path) {
            assertTrue(w.y() == 63.0 || w.y() == 60.0,
                    "a clean FALL leaves NO intermediate ledge (only 63 or 60): " + w);
        }
        assertTrue(path.stream().anyMatch(w -> w.y() == 60.0), "it reaches the pit floor");
        Vec3d last = path.get(path.size() - 1);
        assertEquals(4.5, last.x(), 1.0E-9);
        assertEquals(60.0, last.y(), 1.0E-9);
    }

    @Test
    void routesAroundAGapItCannotParkourAcross() {
        // Two floor slabs split by a 2-cell void; a land bridge at z=4..5 is the only crossing.
        // With no PARKOUR edge, the planner must detour over the bridge instead of jumping the gap.
        FakeWorld world = FakeWorld.empty()
                .wall(-5, 63, -5, 0, 63, 5)  // west slab: cells x -5..0, top 64
                .wall(3, 63, -5, 8, 63, 5)   // east slab: cells x 3..8, top 64
                .wall(1, 63, 4, 2, 63, 5);   // land bridge at z=4..5 over the gap
        var route = planner.route(new Vec3d(-3, 65, 0), new Vec3d(6, 65, 0), world, 5000, true);

        assertTrue(route.isPresent(), "a route around the gap (via the bridge) must exist");
        List<Vec3d> path = route.get();
        assertTrue(path.stream().anyMatch(w -> w.z() >= 4.0), "it must use the z=4..5 bridge");
        for (Vec3d w : path) {
            boolean inVoid = (Math.floor(w.x()) == 1 || Math.floor(w.x()) == 2) && Math.floor(w.z()) <= 3;
            assertFalse(inVoid, "no waypoint may float in the void gap: " + w);
        }
        Vec3d last = path.get(path.size() - 1);
        assertEquals(6.5, last.x(), 1.0E-9);
        assertEquals(0.5, last.z(), 1.0E-9);
    }

    /**
     * The headline scenario: the target sits on a raised platform whose only access is an
     * off-line staircase to the SIDE — the platform's own faces are a sheer 3-block rise, and a
     * boxer standing on the floor directly under the platform is a horizontal-distance-zero dead
     * end. Only the vertical heuristic term keeps the search (and its bestSoFar partial) from
     * stalling under the platform: it must find the side stairs and climb them.
     */
    @Test
    void reachesAnElevatedTargetViaOffLineStairs() {
        FakeWorld world = FakeWorld.floorAt(64)
                // Floating platform slab, top 67, over cells x4..5 z0..1 (air beneath: a boxer under
                // it stands at 64 and cannot climb the 3-block face).
                .wall(4, 66, 0, 5, 66, 1)
                // Off-line staircase at z=-1: 65 → 66 → 67, leading onto the platform top.
                .block(2, 64, -1)                                   // top 65
                .block(3, 64, -1).block(3, 65, -1)                  // top 66
                .block(4, 64, -1).block(4, 65, -1).block(4, 66, -1); // top 67 (steps onto the slab)

        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(5, 67, 0), world, 8000, true);

        assertTrue(route.isPresent(), "the elevated target must be reachable via the side stairs");
        List<Vec3d> path = route.get();
        Vec3d last = path.get(path.size() - 1);
        assertEquals(5.5, last.x(), 1.0E-9, "arrives at the goal cell");
        assertEquals(67.0, last.y(), 1.0E-9, "and ON the platform (y=67), not stalled underneath");
        assertTrue(path.stream().anyMatch(w -> w.z() < 0.0), "the route uses the OFF-LINE stairs (z<0)");
        assertTrue(path.stream().anyMatch(w -> w.y() == 65.0), "climbs the 65 step");
        assertTrue(path.stream().anyMatch(w -> w.y() == 66.0), "climbs the 66 step");
    }

    // --- region frontier & determinism --------------------------------------

    @Test
    void haltsAtTheRegionFrontierWithAnInBoundsPartial() {
        // Everything east of x=4 is "unreadable" (another region / unloaded chunk). The search
        // must stop at the frontier and hand back a partial that lives entirely in readable space.
        FakeWorld base = FakeWorld.floorAt(64);
        CollisionView gated = new CollisionView() {
            @Override
            public List<Box> collidingBoxes(Box region) {
                return base.collidingBoxes(region);
            }

            @Override
            public double slipperiness(int x, int y, int z) {
                return base.slipperiness(x, y, z);
            }

            @Override
            public boolean isReadable(int x, int y, int z) {
                return x < 4; // the loaded/region frontier
            }
        };
        var route = planner.route(new Vec3d(0, 65, 0), new Vec3d(8, 65, 0), gated, 4000, true);

        assertTrue(route.isPresent(), "an unreadable frontier yields a partial, not empty");
        List<Vec3d> path = route.get();
        for (Vec3d w : path) {
            assertTrue(w.x() < 4.0, "no waypoint may cross into unreadable space: " + w);
        }
        Vec3d last = path.get(path.size() - 1);
        assertTrue(last.x() >= 2.0, "the partial still advanced toward the goal: " + last);
    }

    @Test
    void isDeterministicForAFixedInput() {
        FakeWorld world = FakeWorld.floorAt(64).wall(3, 64, -1, 3, 65, 1);
        var first = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 1000, true);
        var second = planner.route(new Vec3d(0, 65, 0), new Vec3d(6, 65, 0), world, 1000, true);
        assertEquals(first, second, "the same input must always yield the same route");
    }

    @Test
    void goalOnStartCellReturnsSingleWaypoint() {
        FakeWorld world = FakeWorld.floorAt(64);
        var route = planner.route(new Vec3d(0.2, 65, 0.2), new Vec3d(0.8, 65, 0.8), world, 500, true);
        assertTrue(route.isPresent());
        assertEquals(1, route.get().size(), "same-cell goal collapses to one waypoint");
        assertEquals(0.5, route.get().get(0).x(), 1.0E-9);
        assertEquals(0.5, route.get().get(0).z(), 1.0E-9);
    }
}

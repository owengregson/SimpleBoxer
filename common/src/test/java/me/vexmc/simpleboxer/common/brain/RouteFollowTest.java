package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/** Pins for the follower's waypoint-consume truth table and look-ahead join. */
class RouteFollowTest {

    private static Perception.SelfState self(double x, double y, double z, boolean onGround) {
        return new Perception.SelfState(x, y, z, Vec3d.ZERO, onGround, false,
                1.0, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
    }

    // --- LEVEL: along-segment plane crossing -----------------------------------

    @Test
    void levelWaypointConsumesOnPlaneCrossing() {
        Vec3d prev = new Vec3d(0.5, 64, 0.5);
        Vec3d wp = new Vec3d(3.5, 64, 0.5);
        // segDir = +X; boxer 0.1 PAST the plane, laterally offset and airborne:
        // toWaypoint·segDir = −0.1 ≤ 0 → consumed (corner cuts advance, never reverse).
        assertTrue(Brain.waypointConsumed(self(3.6, 64.4, 0.9, false), prev, wp, null));
        // 0.1 short of the plane → not yet.
        assertFalse(Brain.waypointConsumed(self(3.4, 64.0, 0.5, true), prev, wp, null));
        // Standing exactly on it: dot = 0 → consumed.
        assertTrue(Brain.waypointConsumed(self(3.5, 64.0, 0.5, true), prev, wp, null));
    }

    // --- ASCEND: strict stand-on ------------------------------------------------

    @Test
    void ascendWaypointNeedsAGroundedStandOn() {
        Vec3d prev = new Vec3d(1.5, 64, 0.5);
        Vec3d wp = new Vec3d(2.5, 65, 0.5);
        // Standing on the riser top → consumed.
        assertTrue(Brain.waypointConsumed(self(2.6, 65.0, 0.5, true), prev, wp, null));
        // Under it, horizontally within a block (the under-platform false-advance
        // the stand-on rule exists to prevent) → NOT consumed.
        assertFalse(Brain.waypointConsumed(self(2.5, 64.0, 0.5, true), prev, wp, null));
        // Mid-jump flyby at its level → NOT consumed (air never advances a riser).
        assertFalse(Brain.waypointConsumed(self(2.5, 65.4, 0.5, false), prev, wp, null));
    }

    // --- DROP: grounded at the landing, overshoot-aware --------------------------

    @Test
    void dropWaypointConsumesInPlaceAndOnOvershoot() {
        Vec3d prev = new Vec3d(1.5, 69, 0.5);   // the lip (or the route origin)
        Vec3d wp = new Vec3d(2.5, 64, 0.5);     // the validated landing, 5 below
        Vec3d next = new Vec3d(6.5, 64, 0.5);
        // Landed on the column → consumed by the radius arm.
        assertTrue(Brain.waypointConsumed(self(2.4, 64.0, 0.5, true), prev, wp, next));
        // Mid-fall above the landing → NOT consumed: air control keeps pointing
        // at the validated column until touchdown.
        assertFalse(Brain.waypointConsumed(self(2.5, 66.0, 0.5, false), prev, wp, next));
        // Sprint overshoot: grounded 2.5 past the landing — horizontal² to the
        // landing is 6.25 but only 2.25 to the NEXT waypoint → consumed (the
        // follower must never steer BACKWARDS onto the landing cell).
        assertTrue(Brain.waypointConsumed(self(5.0, 64.0, 0.5, true), prev, wp, next));
        // The same overshoot with NO next waypoint → not consumed: the terminal
        // landing is judged by the stall clock, not guessed.
        assertFalse(Brain.waypointConsumed(self(5.0, 64.0, 0.5, true), prev, wp, null));
    }

    // --- look-ahead join ----------------------------------------------------------

    @Test
    void lookaheadMarchesAlongThePolyline() {
        // origin (0.5,64,0.5) → wp0 (3.5,64,0.5) → wp1 (3.5,64,4.5): a turn.
        List<Vec3d> path = List.of(new Vec3d(3.5, 64, 0.5), new Vec3d(3.5, 64, 4.5));
        Vec3d origin = new Vec3d(0.5, 64, 0.5);
        // Boxer at (2.0, 64, 0.7): projection onto [origin→wp0] is (2.0, 0.5);
        // wp0 sits 1.5 ahead < 1.75 → 0.25 marches onto the next segment:
        // steer point (3.5, 64, 0.75) — smoothly INTO the turn, no cell-centre stop.
        Vec3d point = Brain.lookaheadPoint(new Vec3d(2.0, 64, 0.7), origin, path, 0, 1.75);
        assertEquals(3.5, point.x(), 1.0E-9);
        assertEquals(0.75, point.z(), 1.0E-9);
    }

    @Test
    void lookaheadNeverCrossesARiserOrALanding() {
        // The cursor waypoint IS a 5-drop landing → the steer point is the
        // landing column itself, regardless of the look-ahead.
        List<Vec3d> drop = List.of(new Vec3d(2.5, 64, 0.5), new Vec3d(6.5, 64, 0.5));
        Vec3d point = Brain.lookaheadPoint(new Vec3d(1.0, 69, 0.5),
                new Vec3d(0.5, 69, 0.5), drop, 0, 1.75);
        assertEquals(2.5, point.x(), 1.0E-9);
        assertEquals(0.5, point.z(), 1.0E-9);

        // A LEVEL cursor whose NEXT waypoint is an ascend: the march stops AT the
        // level waypoint instead of cutting into the riser's face.
        List<Vec3d> riser = List.of(new Vec3d(3.5, 64, 0.5), new Vec3d(4.5, 65, 0.5));
        Vec3d stop = Brain.lookaheadPoint(new Vec3d(3.0, 64, 0.5),
                new Vec3d(0.5, 64, 0.5), riser, 0, 1.75);
        assertEquals(3.5, stop.x(), 1.0E-9);
        assertEquals(0.5, stop.z(), 1.0E-9);
    }
}

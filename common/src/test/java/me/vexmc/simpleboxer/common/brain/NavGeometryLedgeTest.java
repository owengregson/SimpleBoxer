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
 * Pins for the deep ground scan and the reworked ledge probe. Geometry: a rim
 * strip top 65 over x ∈ [−4, 1] with a lower shelf beyond it (x ∈ [1, 6]);
 * the boxer stands at the LedgeKeyGuardTest rim stance (0.95, 65, 0.5), whose
 * +X-shifted probe box (x ∈ [1.2, 1.8]) hangs wholly past the rim.
 */
class NavGeometryLedgeTest {

    private static final Vec3d EAST = new Vec3d(1.0, 0.0, 0.0);

    private static FakeWorld shelfWorld(double shelfTop) {
        return FakeWorld.empty()
                .box(new Box(-4, 64, -4, 1, 65, 8))
                .box(new Box(1, shelfTop - 1.0, -4, 6, shelfTop, 8));
    }

    @Test
    void groundExactlyAtTheBudgetBoundCountsAsGround() {
        // Shelf top 62 = exactly 3.0 below the rim. The OLD probe-step scan
        // shifted the box down by at most 3.0, where its minY merely TOUCHED the
        // shelf top — strict Box.intersects rejected it and an exactly-3 drop
        // read as a ledge (the descent report's boundary defect). The ground
        // scan is inclusive at the bound.
        Box stance = NavGeometry.playerBox(0.95, 65.0, 0.5);
        assertFalse(NavGeometry.ledgeAhead(shelfWorld(62.0), stance, EAST,
                NavGeometry.LOOK_AHEAD, 3.0));
    }

    @Test
    void groundJustBeyondTheBudgetIsALedge() {
        // Shelf top 61.5 = 3.5 below: a ledge at budget 3.0, ground at 3.5.
        Box stance = NavGeometry.playerBox(0.95, 65.0, 0.5);
        assertTrue(NavGeometry.ledgeAhead(shelfWorld(61.5), stance, EAST,
                NavGeometry.LOOK_AHEAD, 3.0));
        assertFalse(NavGeometry.ledgeAhead(shelfWorld(61.5), stance, EAST,
                NavGeometry.LOOK_AHEAD, 3.5));
    }

    @Test
    void deepGroundHeightFindsTheFirstSurfaceWithinTheWindow() {
        // From feet 65 over the shelf (top 62): the highest top at or below the
        // feet within 13 blocks is the shelf itself.
        assertEquals(62.0, NavGeometry.deepGroundHeight(shelfWorld(62.0),
                3.5, 0.5, 65.0, 13.0), 1.0E-9);
        // Bound the window above it (2.0) and it vanishes.
        assertTrue(Double.isNaN(NavGeometry.deepGroundHeight(shelfWorld(62.0),
                3.5, 0.5, 65.0, 2.0)));
    }

    @Test
    void unreadableColumnReadsAsNoGround() {
        FakeWorld base = shelfWorld(62.0);
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
                return y >= 64; // the scan needs 65 → 51 readable
            }
        };
        assertTrue(Double.isNaN(NavGeometry.deepGroundHeight(gated, 3.5, 0.5, 65.0, 13.0)));
    }
}

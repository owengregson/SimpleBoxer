package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.physics.Box;
import org.junit.jupiter.api.Test;

/**
 * Pins for {@link NavGeometry#ceilingGap}: the headroom probe that decides
 * whether a roof is a crit roof. The probe climbs in 0.1 steps and returns
 * (first colliding step − 0.1), so a true gap of g reads back as g rounded
 * down to the probe grid — {@link Box#intersects} is strict, so a probe whose
 * top merely TOUCHES the roof underside does not collide.
 */
class NavGeometryCeilingGapTest {

    /** The standing box at the origin: feet at y = 0, head top at y = 1.8. */
    private static Box standing() {
        return NavGeometry.playerBox(0.0, 0.0, 0.0);
    }

    /** A room: floor top at y = 0 plus a roof whose UNDERSIDE is at {@code underside}. */
    private static FakeWorld room(double underside) {
        return FakeWorld.floorAt(0.0)
                .box(new Box(-8.0, underside, -8.0, 8.0, underside + 1.0, 8.0));
    }

    @Test
    void threeBlockRoomReadsTheCanonicalGap() {
        // Roof underside 3.0, head top 1.8 -> true gap 1.2. The +1.2 probe puts
        // the top AT 3.0 (touching, no hit); the +1.3 step overlaps -> returns
        // 1.3 - 0.1 = 1.2.
        assertEquals(1.2, NavGeometry.ceilingGap(room(3.0), standing()), 1.0E-9);
    }

    @Test
    void tightRoomQuantizesDownToTheProbeGrid() {
        // Underside 2.5 -> true gap 0.7: the +0.7 probe touches (no hit), the
        // +0.8 step overlaps -> returns 0.8 - 0.1 = 0.7.
        assertEquals(0.7, NavGeometry.ceilingGap(room(2.5), standing()), 1.0E-9);
    }

    @Test
    void flushRoofReadsZero() {
        // Underside 1.8 sits directly on the head: the first +0.1 probe already
        // overlaps -> 0.1 - 0.1 = 0.0.
        assertEquals(0.0, NavGeometry.ceilingGap(room(1.8), standing()), 0.0);
    }

    @Test
    void openSkyReadsInfinite() {
        assertTrue(Double.isInfinite(NavGeometry.ceilingGap(FakeWorld.floorAt(0.0), standing())),
                "nothing within the 1.3 probe roof means no roof at all");
    }

    @Test
    void roofJustPastTheApexReadsOpen() {
        // Underside 3.1 -> true gap 1.3, exactly the probe max: the +1.3 step
        // puts the top AT 3.1 (touching) and no further step exists -> OPEN.
        // Correct by design: 1.3 exceeds the 1.2523 open-air apex, so this roof
        // can never clip the arc and is not a crit roof.
        assertTrue(Double.isInfinite(NavGeometry.ceilingGap(room(3.1), standing())));
    }
}

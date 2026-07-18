package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Hand-walked station pins for the straight-corridor probe. The platform arena
 * everywhere: floor top 64, 3×3 pillar over cells (−1..1)² with top 69.
 */
class CorridorProbeTest {

    private static FakeWorld platformArena() {
        return FakeWorld.floorAt(64).wall(-1, 64, -1, 1, 68, 1); // pillar top 69
    }

    @Test
    void flatOpenLineIsClear() {
        // Six stations, all ground 64, headroom everywhere, end level == target level.
        assertTrue(CorridorProbe.clear(FakeWorld.floorAt(64),
                new Vec3d(0.5, 64, 0.5), new Vec3d(6.5, 64, 0.5), 3.0));
    }

    @Test
    void survivableDropIsClear() {
        // From (0.5,69,0.5) toward (6.5,64,0.5), span 6, stations at x 1.5..6.5:
        //  st1 (1.5): shallow window [65, 70.25] → pillar top 69, rise 0;
        //  st2 (2.5): shallow window sees nothing (floor 64 < 65) → deep scan
        //             bounded at 69−13 → 64; drop 5 ≤ 13; headroom clear;
        //  st3..st6: level 64. Chain ends 64 == target ground 64 → clear.
        assertTrue(CorridorProbe.clear(platformArena(),
                new Vec3d(0.5, 69, 0.5), new Vec3d(6.5, 64, 0.5), 13.0));
    }

    @Test
    void beyondBudgetDropFails() {
        // Same line, budget 3: st2's deep scan is bounded at 69 − 3 = 66 → NaN.
        assertFalse(CorridorProbe.clear(platformArena(),
                new Vec3d(0.5, 69, 0.5), new Vec3d(6.5, 64, 0.5), 3.0));
    }

    @Test
    void lineUnderARaisedTargetIsNotACorridor() {
        // Target ON the pillar, boxer on the floor 6 away: the stations chain at
        // 64 until the pillar column, where there is no standing room under the
        // solid pillar (headroom refuses); and any walk-under line (a floating
        // slab, see the Brain-level combat arena) ends its chain below the
        // target's own ground, which the end-level check refuses. Either way the
        // case that genuinely needs the planner is never swallowed.
        assertFalse(CorridorProbe.clear(platformArena(),
                new Vec3d(6.5, 64, 0.5), new Vec3d(0.5, 69, 0.5), 13.0));
    }

    @Test
    void oneJumpRiseIsClearASecondFails() {
        // A 1-block step at x=2: rise 1.0 spends the single jump allowance.
        FakeWorld oneStep = FakeWorld.floorAt(64)
                .block(2, 64, -1).block(2, 64, 0).block(2, 64, 1);
        assertTrue(CorridorProbe.clear(oneStep,
                new Vec3d(0.5, 64, 0.5), new Vec3d(6.5, 64, 0.5), 3.0));
        // A second separated step at x=4 needs a second jump → not a corridor.
        FakeWorld twoSteps = FakeWorld.floorAt(64)
                .block(2, 64, -1).block(2, 64, 0).block(2, 64, 1)
                .block(4, 64, -1).block(4, 64, 0).block(4, 64, 1);
        assertFalse(CorridorProbe.clear(twoSteps,
                new Vec3d(0.5, 64, 0.5), new Vec3d(6.5, 64, 0.5), 3.0));
    }

    @Test
    void tallWallFailsOnHeadroom() {
        // A 2-tall wall at x=3: the station chains rise 1.0 onto the first block
        // (top 65, inside the shallow window) but the standing box there
        // intersects the second block (y 65..66) → no standing room.
        FakeWorld world = FakeWorld.floorAt(64);
        for (int z = -2; z <= 2; z++) {
            world.block(3, 64, z).block(3, 65, z);
        }
        assertFalse(CorridorProbe.clear(world,
                new Vec3d(0.5, 64, 0.5), new Vec3d(6.5, 64, 0.5), 3.0));
    }

    @Test
    void beyondTheProbeHorizonIsNotClear() {
        // span 17 > MAX_STRIDES 16 — farther elevated targets go to the planner.
        assertFalse(CorridorProbe.clear(FakeWorld.floorAt(64),
                new Vec3d(0.5, 64, 0.5), new Vec3d(17.5, 64, 0.5), 13.0));
    }

    @Test
    void voidBeyondTheBudgetFails() {
        // A slab over void: the first off-slab station deep-scans 13 blocks of
        // nothing → NaN → fail (and the target column has no ground either).
        FakeWorld world = FakeWorld.empty().wall(-2, 63, -2, 0, 63, 2); // top 64
        assertFalse(CorridorProbe.clear(world,
                new Vec3d(0.5, 64, 0.5), new Vec3d(6.5, 64, 0.5), 13.0));
    }

    @Test
    void unreadableColumnBelowFailsTheLine() {
        FakeWorld base = platformArena();
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
                return y >= 66; // st2's deep scan must read 69 → 55: gated off
            }
        };
        assertFalse(CorridorProbe.clear(gated,
                new Vec3d(0.5, 69, 0.5), new Vec3d(6.5, 64, 0.5), 13.0));
    }
}

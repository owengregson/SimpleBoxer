package me.vexmc.simpleboxer.common.physics;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed pins for the client integrator. The motion constants and
 * derivations live in docs/research/2026-06-06-client-motion-pins.md; the
 * knock-trajectory expectations are the values measured on REAL era servers
 * during Mental's wire campaign — the emulator must fly like the era flew.
 */
class ClientPhysicsTest {

    private static final double WALK_TERMINAL = 0.098 / 0.454;          // 0.215859…
    private static final double SPRINT_TERMINAL = 0.1274 / 0.454;       // 0.280616…
    private static final double SPRINT_AIR_TERMINAL = 0.025999999 * 0.98 / 0.09; // 0.283111…
    private static final double SNEAK_TERMINAL = 0.3 * 0.098 / 0.454;   // 0.064757…

    /** Flat world: an optional infinite floor with its top at y = 0, plus extras. */
    private static final class FlatWorld implements CollisionView {
        final double slip;
        final boolean floor;
        final List<Box> extras = new ArrayList<>();

        FlatWorld(double slip, boolean floor) {
            this.slip = slip;
            this.floor = floor;
        }

        @Override
        public List<Box> collidingBoxes(Box region) {
            List<Box> found = new ArrayList<>();
            if (floor && region.minY() < 0.0) {
                found.add(new Box(region.minX() - 1, -1.0, region.minZ() - 1,
                        region.maxX() + 1, 0.0, region.maxZ() + 1));
            }
            for (Box extra : extras) {
                if (extra.intersects(region)) {
                    found.add(extra);
                }
            }
            return found;
        }

        @Override
        public double slipperiness(int blockX, int blockY, int blockZ) {
            return slip;
        }
    }

    private static FlatWorld stone() {
        return new FlatWorld(0.6, true);
    }

    private static ClientPhysics grounded(CollisionView world) {
        ClientPhysics physics = new ClientPhysics(0.0, 0.0, 0.0);
        // Two settle ticks: the first only establishes downward motion
        // (vy 0 → −0.0784); the second collides and raises the ground flag.
        for (int tick = 0; tick < 3 && !physics.onGround(); tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
        }
        return physics;
    }

    @Test
    void standingSettlesToTheGravityEquilibrium() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        for (int tick = 0; tick < 5; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
        }
        assertEquals(0.0, physics.y(), 1.0E-9, "standing feet stay on the floor");
        assertTrue(physics.onGround(), "standing player is grounded");
        // (0 − 0.08) × 0.98 — the parked equilibrium every era shares.
        assertEquals(-0.0784, physics.velocity().y(), 1.0E-9, "vy equilibrium");
    }

    @Test
    void walkReachesTheCanonTerminalSpeed() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        double before = 0.0;
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            before = physics.z();
            physics.step(MoveInput.walkForward(), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(WALK_TERMINAL, perTick, 1.0E-6, "walk displacement per tick (4.317 m/s)");
    }

    @Test
    void sprintReachesTheCanonTerminalSpeed() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            double before = physics.z();
            physics.step(MoveInput.sprintForward(), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(SPRINT_TERMINAL, perTick, 1.0E-6, "sprint displacement per tick (5.612 m/s)");
    }

    @Test
    void sneakCrawlsAtThirtyPercent() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            double before = physics.z();
            physics.step(new MoveInput(1.0, 0.0, false, false, true), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(SNEAK_TERMINAL, perTick, 1.0E-6, "sneak displacement per tick");
    }

    @Test
    void airborneSprintConvergesOnAirAcceleration() {
        FlatWorld world = new FlatWorld(0.6, false); // bottomless — free fall
        ClientPhysics physics = new ClientPhysics(0.0, 0.0, 0.0);
        double perTick = 0.0;
        for (int tick = 0; tick < 300; tick++) {
            double before = physics.z();
            physics.step(MoveInput.sprintForward(), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(SPRINT_AIR_TERMINAL, perTick, 1.0E-6, "airborne sprint displacement per tick");
    }

    @Test
    void jumpApexMatchesTheKnownArc() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, world);
        double apex = physics.y();
        for (int tick = 0; tick < 20; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
            apex = Math.max(apex, physics.y());
        }
        // 0.42 → 0.3332 → 0.2481 → 0.1648 → 0.0831 → 0.0030, summed.
        assertEquals(1.252203352512, apex, 1.0E-9, "jump apex");
        assertTrue(physics.onGround(), "jumper lands again");
        assertEquals(0.0, physics.y(), 1.0E-9, "lands back on the floor");
    }

    @Test
    void jumpBoostRaisesTheStamp() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.setJumpBoostAmplifier(1); // Jump Boost II = 0.42 + 0.2
        physics.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, world);
        assertEquals(0.62, physics.y(), 1.0E-9, "first rise is the boosted impulse");
    }

    @Test
    void velocityPacketReplacesAndTheLaunchTickDecaysAtGroundDrag() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        // The era sprint stamp, along +Z. REPLACE semantics: whatever the
        // victim carried is gone; the launch tick then decays at the
        // PRE-move ground drag (0.546) even though the move lifts off.
        physics.applyVelocity(0.0, 0.4607, 0.9);
        physics.step(MoveInput.IDLE, 0.0f, world);
        assertEquals(0.9, physics.z(), 1.0E-9, "first move ships the full stamp");
        assertEquals(0.4607, physics.y(), 1.0E-9, "first rise is the stamped vertical");
        assertEquals(0.9 * 0.546, physics.velocity().z(), 1.0E-9, "launch-tick ground decay");
        assertEquals((0.4607 - 0.08) * 0.98, physics.velocity().y(), 1.0E-9, "post-launch vy");
        assertFalse(physics.onGround(), "airborne after liftoff");
    }

    @Test
    void sprintKnockSettlesAtTheMeasuredEraFlight() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.0, 0.4607, 0.9);
        int ticks = 0;
        do {
            physics.step(MoveInput.IDLE, 0.0f, world);
            ticks++;
        } while (ticks < 200 && (!physics.onGround() || Math.abs(physics.velocity().z()) > 1.0E-3));
        // Mental's wire campaign measured the full-stamp sprint knock at
        // ≈ 4.948 blocks settled on real vanilla 1.8.9. The emulator runs
        // the same integrator the era ran for input-free victims — it must
        // land where the era landed.
        assertEquals(4.948, physics.z(), 0.02, "settled sprint-knock distance");
        assertTrue(ticks < 60, "settles in era time, not asymptotically");
    }

    @Test
    void iceLaunchDecaysAtIceDrag() {
        FlatWorld world = new FlatWorld(0.98, true);
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.0, 0.4607, 0.9);
        physics.step(MoveInput.IDLE, 0.0f, world);
        // slip × 0.91 = 0.8918 — the decay-on-launch friction IS the block.
        assertEquals(0.9 * 0.98 * 0.91, physics.velocity().z(), 1.0E-9, "ice launch decay");
    }

    @Test
    void explosionAddsInsteadOfReplacing() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.1, 0.0, 0.2);
        physics.addVelocity(0.05, 0.3, -0.1);
        assertEquals(0.15, physics.velocity().x(), 1.0E-12);
        assertEquals(0.3, physics.velocity().y(), 1.0E-12);
        assertEquals(0.1, physics.velocity().z(), 1.0E-12);
    }

    @Test
    void jumpKeepsAStrongerKnockVertical() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.0, 0.6, 0.0);
        physics.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, world);
        // jumpFromGround takes max(jumpPower, vy): the knock's 0.6 wins.
        assertEquals(0.6, physics.y(), 1.0E-9, "stronger knock vertical survives the jump");
    }

    @Test
    void sprintJumpAddsTheFacingPush() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.step(new MoveInput(1.0, 0.0, true, true, false), 0.0f, world);
        // Jump push 0.2 along yaw 0 (+Z) + sprint ground accel 0.13 × 0.98 ×
        // (0.21600002 / 0.216) — vanilla's magic float is not exactly 0.6³,
        // leaving a deliberate +9.26e-8 relative excess on ground accel.
        assertEquals(0.2 + 0.13 * 0.98 * (0.21600002 / 0.216), physics.z(), 1.0E-12,
                "sprint-jump first move");
        assertEquals(0.42, physics.y(), 1.0E-9, "jump rise");
    }

    @Test
    void wallStopsTheKnockAndZeroesTheAxis() {
        FlatWorld world = stone();
        world.extras.add(new Box(-5.0, 0.0, 2.0, 5.0, 3.0, 3.0));
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.0, 0.0, 0.9);
        boolean sawCollision = false;
        for (int tick = 0; tick < 10; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
            // The flag is per-tick truth (vanilla semantics): once stopped,
            // later idle ticks attempt no horizontal motion and clear it.
            sawCollision |= physics.horizontalCollision();
        }
        // Feet stop half a width short of the wall face at z = 2.
        assertEquals(2.0 - 0.3, physics.z(), 1.0E-7, "stopped at the wall");
        assertEquals(0.0, physics.velocity().z(), 1.0E-9, "collided axis zeroed");
        assertTrue(sawCollision, "horizontal collision reported on the stopping tick");
    }

    @Test
    void stepsUpASlabWhileWalking() {
        FlatWorld world = stone();
        world.extras.add(new Box(-5.0, 0.0, 1.0, 5.0, 0.5, 9.0));
        ClientPhysics physics = grounded(world);
        for (int tick = 0; tick < 40; tick++) {
            physics.step(MoveInput.walkForward(), 0.0f, world);
        }
        assertEquals(0.5, physics.y(), 1.0E-7, "walked up onto the slab");
        assertTrue(physics.z() > 1.0, "kept moving past the slab edge");
        assertTrue(physics.onGround(), "grounded on the slab");
    }

    @Test
    void headBumpZeroesVerticalVelocity() {
        FlatWorld world = stone();
        world.extras.add(new Box(-5.0, 2.0, -5.0, 5.0, 3.0, 5.0));
        ClientPhysics physics = grounded(world);
        physics.step(new MoveInput(0.0, 0.0, true, false, false), 0.0f, world);
        // 1.8 of headroom under a ceiling at 2.0: the 0.42 rise clamps to 0.2.
        assertEquals(0.2, physics.y(), 1.0E-9, "rise clamps at the ceiling");
        double highest = physics.y();
        for (int tick = 0; tick < 5; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
            highest = Math.max(highest, physics.y());
        }
        assertEquals(0.2, highest, 1.0E-9, "never re-rises after the bump");
    }

    @Test
    void heldInputCurvesTheKnockTrajectory() {
        FlatWorld world = stone();
        ClientPhysics knocked = grounded(world);
        ClientPhysics passive = grounded(world);
        knocked.applyVelocity(0.0, 0.4607, 0.9);
        passive.applyVelocity(0.0, 0.4607, 0.9);
        for (int tick = 0; tick < 30; tick++) {
            knocked.step(new MoveInput(0.0, 1.0, false, false, false), 0.0f, world); // strafe left
            passive.step(MoveInput.IDLE, 0.0f, world);
        }
        assertTrue(knocked.x() > 0.5, "held strafe steers the flight");
        assertEquals(0.0, passive.x(), 1.0E-9, "input-free flight stays straight");
    }
}

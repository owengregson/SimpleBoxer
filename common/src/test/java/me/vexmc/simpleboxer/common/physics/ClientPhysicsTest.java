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
        /** When true every cell is a cobweb (no collision box, only a stuck stamp). */
        final boolean web;
        final List<Box> extras = new ArrayList<>();

        FlatWorld(double slip, boolean floor) {
            this(slip, floor, false);
        }

        FlatWorld(double slip, boolean floor, boolean web) {
            this.slip = slip;
            this.floor = floor;
            this.web = web;
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

        @Override
        public Vec3d stuckMultiplier(int blockX, int blockY, int blockZ) {
            return web ? new Vec3d(0.25, 0.05, 0.25) : null;
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

    /**
     * Item use (blocking, eating, drawing) multiplies both input impulses ×0.2
     * on a real client ({@code LocalPlayer.aiStep}); the drag recursion is
     * linear in the acceleration, so the terminal speed is exactly one fifth
     * of the sprint terminal: (0.2 × 0.1274) / 0.454 = 0.0561233… b/t
     * (1.12 m/s — the crawl an enemy sees from a real blockhitter).
     */
    @Test
    void usingAnItemSlowsSprintToExactlyOneFifth() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        MoveInput blockhit = MoveInput.sprintForward().withUsingItem(true);
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            double before = physics.z();
            physics.step(blockhit, 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(SPRINT_TERMINAL * 0.2, perTick, 1.0E-7,
                "blockhit displacement per tick (1.12 m/s)");
    }

    /**
     * The sprint-jump burst mid-use: from the using-sprint steady state the
     * jump tick carries last tick's post-drag velocity (terminal × 0.546),
     * gains the 0.2 sprint-jump push, and accelerates one using-scaled ground
     * tick (0.2 × 0.98 × 0.13 × magic ≈ 0.025480) —
     * 0.0561233 × 0.546 + 0.2 + 0.0254800 = 0.2561233… blocks in that tick,
     * against 0.4806166 unslowed: the "jumps forward somehow" burst halved.
     */
    @Test
    void sprintJumpMidUseShipsTheSlowedBurst() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        MoveInput blockhit = MoveInput.sprintForward().withUsingItem(true);
        for (int tick = 0; tick < 200; tick++) {
            physics.step(blockhit, 0.0f, world);
        }
        double before = physics.z();
        physics.step(new MoveInput(1.0, 0.0, true, true, false, true), 0.0f, world);
        double jumpTick = physics.z() - before;
        double expected = SPRINT_TERMINAL * 0.2 * 0.546 + 0.2
                + 0.2 * 0.98 * 0.13 * (0.21600002 / 0.216);
        assertEquals(expected, jumpTick, 1.0E-7, "mid-use sprint-jump burst");
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
    void speedAttributeScalesGroundSprintLinearly() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        // Speed I is a +20% MULTIPLY_TOTAL modifier on the movement-speed
        // attribute; ground acceleration is attribute-proportional, so the
        // terminal displacement scales linearly. Air acceleration is the
        // fixed 0.026 and does NOT scale (pinned by
        // airborneSprintConvergesOnAirAcceleration) — vanilla truth.
        physics.setWalkSpeed(0.1 * 1.2);
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            double before = physics.z();
            physics.step(MoveInput.sprintForward(), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(SPRINT_TERMINAL * 1.2, perTick, 1.0E-6, "Speed I sprint displacement per tick");
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
    void diagonalIntoWallPreservesParallelVelocity() {
        // A body moving diagonally into a wall slides ALONG it — vanilla collide
        // zeroes only the blocked axis. Pins the physics slide independently of the
        // brain's steering (the "sticking" fix lives upstream, not here).
        FlatWorld world = stone();
        world.extras.add(new Box(-5.0, 0.0, 2.0, 5.0, 3.0, 3.0)); // Z-facing wall at z = 2
        ClientPhysics physics = grounded(world);
        double startX = physics.x();
        physics.applyVelocity(0.3, 0.0, 0.9); // diagonal: +X (parallel) and +Z (into wall)
        for (int tick = 0; tick < 10; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
        }
        // Z clamps at the wall face; X kept moving — the parallel component survived.
        assertEquals(2.0 - 0.3, physics.z(), 1.0E-7, "z stopped at the wall face");
        assertTrue(physics.x() > startX + 0.05,
                "slid along the wall in x (parallel velocity preserved, not glued)");
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
        // Ceiling at 2.0 over feet at 0: the 0.42 rise clamps to the exact gap
        // 2.0 − (double) 1.8f = 2.0 − 1.7999999523162842 = 0.20000004768371582
        // (the float-promoted height, byte-identical to the server's box).
        assertEquals(0.20000004768371582, physics.y(), 1.0E-9, "rise clamps at the ceiling");
        double highest = physics.y();
        for (int tick = 0; tick < 5; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
            highest = Math.max(highest, physics.y());
        }
        assertEquals(0.20000004768371582, highest, 1.0E-9, "never re-rises after the bump");
    }

    @Test
    void sprintHitSelfSlowMultipliesHorizontalOnly() {
        FlatWorld world = stone();
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.25, 0.4, -0.1);
        // The attacker's own ×0.6 on a landed full-meter sprint hit — the
        // mechanic behind "high CPS reduces your own knockback".
        physics.multiplyHorizontalVelocity(0.6);
        assertEquals(0.15, physics.velocity().x(), 1.0E-12, "x slowed");
        assertEquals(0.4, physics.velocity().y(), 1.0E-12, "vertical untouched");
        assertEquals(-0.06, physics.velocity().z(), 1.0E-12, "z slowed");
    }

    @Test
    void pushAwayMatchesVanillaShoveMath() {
        // absMax 0.4 → divisor √0.4 ≈ 0.6325 (vanilla divides by √absMax,
        // not the norm); 1/√0.4 > 1 clamps to 1; shove = −(dx/√d) × 0.05F.
        Vec3d shove = ClientPhysics.pushAway(0.0, 0.0, 0.4, 0.0);
        assertEquals(-(0.4 / Math.sqrt(0.4)) * ClientPhysics.PUSH_STRENGTH, shove.x(), 1.0E-12,
                "head-on overlap shove");
        assertEquals(0.0, shove.z(), 1.0E-12, "no sideways component head-on");
        // Diagonal: both axes share the √absMax divisor and the 0.05 scale.
        Vec3d diagonal = ClientPhysics.pushAway(0.0, 0.0, 0.3, -0.4);
        double divisor = Math.sqrt(0.4);
        assertEquals(-(0.3 / divisor) * ClientPhysics.PUSH_STRENGTH, diagonal.x(), 1.0E-12);
        assertEquals((0.4 / divisor) * ClientPhysics.PUSH_STRENGTH, diagonal.z(), 1.0E-12);
        // The 0.01 dead zone: perfectly stacked entities shove nobody.
        assertEquals(Vec3d.ZERO, ClientPhysics.pushAway(0.0, 0.0, 0.005, 0.005),
                "degenerate stack is inert");
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

    @Test
    void webClampsHorizontalAndVerticalSpeed() {
        // Web everywhere, no floor: isolate the stuck clamp from ground collision.
        // Vanilla Entity.move, when stuckSpeedMultiplier is set, multiplies the
        // MOVEMENT by (0.25, 0.05, 0.25) and then zeroes deltaMovement — so this
        // tick ships a quartered horizontal / twentieth vertical step, and the
        // carried velocity is wiped (vertical then restarts at the gravity stamp).
        FlatWorld world = new FlatWorld(0.6, false, true);
        ClientPhysics physics = new ClientPhysics(0.0, 0.0, 0.0);
        physics.applyVelocity(0.8, 0.4, 0.8);
        physics.step(MoveInput.IDLE, 0.0f, world);
        assertEquals(0.8 * 0.25, physics.x(), 1.0E-12, "web quarters the horizontal step (x)");
        assertEquals(0.8 * 0.25, physics.z(), 1.0E-12, "web quarters the horizontal step (z)");
        assertEquals(0.4 * 0.05, physics.y(), 1.0E-12, "web cuts the vertical step to a twentieth");
        assertEquals(0.0, physics.velocity().x(), 1.0E-12, "carried x velocity is wiped");
        assertEquals(0.0, physics.velocity().z(), 1.0E-12, "carried z velocity is wiped");
        // Velocity zeroed, then the tick's gravity stamp: (0 − 0.08) × 0.98.
        assertEquals(-0.0784, physics.velocity().y(), 1.0E-9, "vertical restarts at the gravity stamp");
    }

    @Test
    void webTerminalSpeed() {
        // Stone floor blanketed in web. Because the web zeroes carried velocity
        // every tick, the boxer only ever ships ONE tick of ground-walk
        // acceleration (0.098), quartered by the web's x/z stamp — there is no
        // geometric ramp to an air-drag terminal like the open-floor walk.
        FlatWorld world = new FlatWorld(0.6, true, true);
        ClientPhysics physics = grounded(world);
        double perTick = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            double before = physics.z();
            physics.step(MoveInput.walkForward(), 0.0f, world);
            perTick = physics.z() - before;
        }
        assertEquals(0.098 * 0.25, perTick, 1.0E-6, "web walk displacement per tick (0.0245)");
    }

    @Test
    void playerBoxIsByteIdenticalToTheServerRebuild() {
        // EntityDimensions.makeBoundingBox halves the FLOAT width and promotes:
        // (double) (0.6f / 2.0f) = 0.30000001192092896; height (double) 1.8f
        // = 1.7999999523162842. PLAYER_WIDTH/PLAYER_HEIGHT carry the promoted
        // values, and halving the promoted width in doubles is exact (÷2 only
        // decrements the exponent), so the sim box equals the server rebuild
        // bit for bit — deltas are 0.0, not tolerances.
        assertEquals((double) 0.6f, ClientPhysics.PLAYER_WIDTH, 0.0, "width is (double) 0.6f");
        assertEquals((double) 1.8f, ClientPhysics.PLAYER_HEIGHT, 0.0, "height is (double) 1.8f");
        Box box = Box.player(0.0, 0.0, 0.0,
                ClientPhysics.PLAYER_WIDTH, ClientPhysics.PLAYER_HEIGHT);
        assertEquals(-0.30000001192092896, box.minX(), 0.0, "server half-extent, min side");
        assertEquals(0.30000001192092896, box.maxX(), 0.0, "server half-extent, max side");
        assertEquals(-0.30000001192092896, box.minZ(), 0.0, "server half-extent, min side");
        assertEquals(0.30000001192092896, box.maxZ(), 0.0, "server half-extent, max side");
        assertEquals(1.7999999523162842, box.maxY(), 0.0, "server height");
    }

    @Test
    void flushWallRestRoundTripsToZeroServerPenetration() {
        // A knock into the wall at z = 2 parks the box edge exactly on the wall
        // plane: rest center = 2.0 − 0.30000001192092896 = 1.699999988079071
        // (exact — the final approach clamp computes gap = 2.0 − maxZ, and that
        // subtraction is exact by Sterbenz's lemma). Rebuilding the box from the
        // claimed center with the server's promoted half-extent lands maxZ on
        // exactly 2.0: penetration exactly 0.0 — why a vanilla client pressed
        // into a wall is never rejected, and now the boxer isn't either.
        FlatWorld world = stone();
        world.extras.add(new Box(-5.0, 0.0, 2.0, 5.0, 3.0, 3.0));
        ClientPhysics physics = grounded(world);
        physics.applyVelocity(0.0, 0.0, 0.9);
        for (int tick = 0; tick < 10; tick++) {
            physics.step(MoveInput.IDLE, 0.0f, world);
        }
        double half = 0.30000001192092896; // (double) (0.6f / 2.0f)
        assertEquals(2.0 - half, physics.z(), 0.0, "rest center is wallPlane - serverHalf");
        assertEquals(2.0, physics.z() + half, 0.0,
                "server AABB rebuild lands flush: penetration exactly 0.0");
    }

    @Test
    void sweepBacksASubEpsilonOverlapOutInOneTick() {
        // Paper's collideX/Y/Z keep the raw gap, negative down to −1e-7: a box
        // that starts 1e-8 INSIDE a wall is pushed back out by its own sweep.
        // Start center = 2.0 + 1e-8 − half = 1.699999998079071 (box edge
        // 9.99999993922529e-9 past the plane); the sweep's gap is that same
        // −9.99999993922529e-9 (≥ −1e-7, so the shape still clamps) and the
        // move ships it: end center = 2.0 − half = 1.699999988079071 exactly,
        // penetration exactly 0.0. The pre-fix clamp (max(gap, 0.0)) parked the
        // box inside forever. The wall is the only shape on purpose — vanilla's
        // per-shape |d| head check would zero a residual back-out if another
        // shape followed it in the list.
        FlatWorld world = new FlatWorld(0.6, false); // bottomless — isolate the z sweep
        world.extras.add(new Box(-5.0, -1.0, 2.0, 5.0, 3.0, 3.0));
        double half = 0.30000001192092896;
        ClientPhysics physics = new ClientPhysics(0.0, 0.0, 2.0 + 1.0E-8 - half);
        physics.applyVelocity(0.0, 0.0, 0.5);
        physics.step(MoveInput.IDLE, 0.0f, world);
        assertEquals(2.0 - half, physics.z(), 0.0, "backed out to exactly flush");
        assertEquals(0.0, physics.z() + half - 2.0, 0.0, "penetration exactly 0.0");
        assertEquals(0.0, physics.velocity().z(), 0.0, "collided axis zeroed");
        assertTrue(physics.horizontalCollision(), "the back-out still reports the collision");
    }
}

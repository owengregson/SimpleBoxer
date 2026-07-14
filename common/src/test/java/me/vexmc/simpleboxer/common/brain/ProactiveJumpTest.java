package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.brain.Perception.CombatState;
import me.vexmc.simpleboxer.common.brain.Perception.InventoryView;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TerrainView;
import me.vexmc.simpleboxer.common.brain.Perception.UseItemState;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link ProactiveJump}: it must fire before contact for a
 * climbable step, stay silent for a tall wall and for flat ground, and respect
 * its own anti-spam cooldown.
 */
class ProactiveJumpTest {

    private final ProactiveJump jump = new ProactiveJump();

    /** A grounded boxer at (x,y,z) with the given horizontal velocity, not wall-stuck. */
    private static Perception moving(double x, double y, double z, Vec3d velocity) {
        return grounded(x, y, z, velocity, false);
    }

    private static Perception grounded(double x, double y, double z, Vec3d velocity,
            boolean horizontalCollision) {
        SelfState self = new SelfState(x, y, z, velocity, true, horizontalCollision,
                1.0, 1.0, UseItemState.NONE, false);
        return new Perception(self, null, TerrainView.OPEN, InventoryView.EMPTY,
                CombatState.IDLE, 0);
    }

    private static MoveHeading eastward() {
        // +X heading (unit east): non-still, so the probe has a direction to test.
        return new MoveHeading(new Vec3d(1.0, 0.0, 0.0));
    }

    private static BrainMemory mem() {
        return new BrainMemory(1234L);
    }

    // (a) A 1-block step directly ahead -> JUMP raised before contact.
    @Test
    void hopsOverAOneBlockStep() {
        CollisionView world = FakeWorld.floorAt(64).block(1, 64, 0);
        Perception p = moving(0.0, 64.0, 0.0, new Vec3d(0.25, 0.0, 0.0));

        assertEquals(JumpHint.JUMP, jump.evaluate(p, eastward(), world, mem()));
    }

    // (b) A 3-block-tall wall ahead -> NOT a hop; that needs a detour.
    @Test
    void doesNotHopAWall() {
        CollisionView world = FakeWorld.floorAt(64)
                .block(1, 64, 0).block(1, 65, 0).block(1, 66, 0);
        Perception p = moving(0.0, 64.0, 0.0, new Vec3d(0.25, 0.0, 0.0));

        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), world, mem()));
    }

    // (c) Flat open ground -> NONE.
    @Test
    void doesNotHopOnFlatGround() {
        CollisionView world = FakeWorld.floorAt(64);
        Perception p = moving(0.0, 64.0, 0.0, new Vec3d(0.25, 0.0, 0.0));

        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), world, mem()));
    }

    // (d) After a JUMP, a re-evaluation within the cooldown window stays NONE.
    @Test
    void respectsCooldownAfterHopping() {
        CollisionView world = FakeWorld.floorAt(64).block(1, 64, 0);
        Perception p = moving(0.0, 64.0, 0.0, new Vec3d(0.25, 0.0, 0.0));
        BrainMemory mem = mem();

        assertEquals(JumpHint.JUMP, jump.evaluate(p, eastward(), world, mem));
        // Same tick / same memory: the anti-spam clock must suppress the re-hop.
        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), world, mem));
    }

    // Airborne boxers get no push-off, so no jump even with a step ahead.
    @Test
    void doesNotHopWhileAirborne() {
        CollisionView world = FakeWorld.floorAt(64).block(1, 64, 0);
        SelfState airborne = new SelfState(0.0, 64.5, 0.0, new Vec3d(0.25, 0.0, 0.0),
                false, false, 1.0, 1.0, UseItemState.NONE, false);
        Perception p = new Perception(airborne, null, TerrainView.OPEN,
                InventoryView.EMPTY, CombatState.IDLE, 0);

        assertEquals(JumpHint.NONE, jump.evaluate(p, eastward(), world, mem()));
    }

    // A still heading (no direction to probe) never hops.
    @Test
    void doesNotHopWithoutAHeading() {
        CollisionView world = FakeWorld.floorAt(64).block(1, 64, 0);
        Perception p = moving(0.0, 64.0, 0.0, Vec3d.ZERO);

        assertEquals(JumpHint.NONE, jump.evaluate(p, MoveHeading.STILL, world, mem()));
    }

    // Pressed head-on against a climbable step at rest (velocity ~0, collision
    // flagged) -> still hops, so a boxer that stalled into a step recovers.
    @Test
    void hopsWhenPressedAgainstStepAtRest() {
        CollisionView world = FakeWorld.floorAt(64).block(1, 64, 0);
        Perception p = grounded(0.55, 64.0, 0.0, Vec3d.ZERO, true);

        assertEquals(JumpHint.JUMP, jump.evaluate(p, eastward(), world, mem()));
    }
}

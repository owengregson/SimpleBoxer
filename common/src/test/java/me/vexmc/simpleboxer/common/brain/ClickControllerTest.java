package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.brain.Perception.CombatState;
import me.vexmc.simpleboxer.common.brain.Perception.InventoryView;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TargetState;
import me.vexmc.simpleboxer.common.brain.Perception.TerrainView;
import me.vexmc.simpleboxer.common.brain.Perception.UseItemState;
import me.vexmc.simpleboxer.common.combat.ClickScheduler;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * Behavioral contract for {@link ClickController}: CPS-clocked clicks, reach and
 * aim-cone gating on the ATTACK (never the swing), latency-aware reach
 * prediction, item-use suppression, and attack-before-swing ordering.
 */
class ClickControllerTest {

    private final ClickController controller = new ClickController();

    /** A scheduler that reliably reports a click at {@code nowMs = FIRE_AT}. */
    private static ClickScheduler primedClock() {
        // 50 CPS, no jitter => 20ms interval. The first shouldClick only arms the
        // schedule (returns false); we drive that priming call here so the tick
        // under test is guaranteed to land on a real click.
        ClickScheduler clock = new ClickScheduler(50.0, 0.0, 1L);
        assertFalse(clock.shouldClick(0L), "first tick only arms the schedule");
        return clock;
    }

    private static final long FIRE_AT = 100L;

    private static BrainMemory mem() {
        return new BrainMemory(1234L);
    }

    private static SelfState selfAtOrigin() {
        return new SelfState(0.0, 64.0, 0.0, Vec3d.ZERO,
                true, false, 1.0, 1.0, UseItemState.NONE, false);
    }

    private static TargetState targetAt(double x, double y, double z, Vec3d vel) {
        return new TargetState(x, y, z, y + 1.62, vel, 0.0, 0.0,
                Math.hypot(x, z), false);
    }

    private static Perception perception(SelfState self, TargetState target, int pingMs) {
        return new Perception(self, target, TerrainView.OPEN,
                InventoryView.EMPTY, CombatState.IDLE, pingMs);
    }

    @Test
    void inReachAndAimed_emitsAttackThenSwing() {
        // Target 2 blocks dead ahead (yaw 0 faces +Z); reach 3, wide cone.
        Perception p = perception(selfAtOrigin(), targetAt(0.0, 64.0, 2.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 0.0, primedClock(), FIRE_AT, mem(), out);

        assertEquals(2, out.size(), "a landed hit swings too");
        assertInstanceOf(ActionIntent.Attack.class, out.get(0), "attack precedes swing");
        assertInstanceOf(ActionIntent.Swing.class, out.get(1));
    }

    @Test
    void outOfReach_swingsButDoesNotAttack() {
        Perception p = perception(selfAtOrigin(), targetAt(0.0, 64.0, 10.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 0.0, primedClock(), FIRE_AT, mem(), out);

        assertEquals(1, out.size(), "a whiff still swings");
        assertInstanceOf(ActionIntent.Swing.class, out.get(0));
    }

    @Test
    void offAimCone_swingsButDoesNotAttack() {
        // Target due east and well within reach, but the crosshair points +Z:
        // ~90 degrees off the 30-degree cone, so the attack is gated out.
        Perception p = perception(selfAtOrigin(), targetAt(2.0, 64.0, 0.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 0.0, primedClock(), FIRE_AT, mem(), out);

        assertEquals(1, out.size());
        assertInstanceOf(ActionIntent.Swing.class, out.get(0));
    }

    @Test
    void suppressed_emitsNothing() {
        Perception p = perception(selfAtOrigin(), targetAt(0.0, 64.0, 2.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, true, 0.0, primedClock(), FIRE_AT, mem(), out);

        assertTrue(out.isEmpty(), "no click while mid item-use");
    }

    @Test
    void noClockTick_emitsNothing() {
        // A fresh (unarmed) scheduler never fires on its very first query.
        ClickScheduler cold = new ClickScheduler(50.0, 0.0, 1L);
        Perception p = perception(selfAtOrigin(), targetAt(0.0, 64.0, 2.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 0.0, cold, 0L, mem(), out);

        assertTrue(out.isEmpty(), "no click when the CPS clock is silent");
    }

    @Test
    void noTarget_swingsOnly() {
        Perception p = perception(selfAtOrigin(), null, 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 0.0, primedClock(), FIRE_AT, mem(), out);

        assertEquals(1, out.size());
        assertInstanceOf(ActionIntent.Swing.class, out.get(0));
    }

    @Test
    void latencyPrediction_bringsFastCloserTargetIntoReach() {
        // Currently 4.5 blocks out (beyond a 3.0 reach) but closing at 1 block/tick.
        // With 200ms ping the click lands ~2 ticks later (200/2/50), by which time
        // the target sits at z=2.5 => inside reach. The prediction is load-bearing.
        SelfState self = selfAtOrigin();
        TargetState fastCloser = targetAt(0.0, 64.0, 4.5, new Vec3d(0.0, 0.0, -1.0));

        List<ActionIntent> predicted = new ArrayList<>();
        controller.consider(perception(self, fastCloser, 200), 0.0f, 3.0, 30.0,
                false, 0.0, primedClock(), FIRE_AT, mem(), predicted);
        assertInstanceOf(ActionIntent.Attack.class, predicted.get(0),
                "extrapolated position is within reach -> attack lands");

        // Same geometry, zero ping: no extrapolation, still out of reach -> whiff.
        List<ActionIntent> here = new ArrayList<>();
        controller.consider(perception(self, fastCloser, 0), 0.0f, 3.0, 30.0,
                false, 0.0, primedClock(), FIRE_AT, mem(), here);
        assertEquals(1, here.size(), "without prediction the target is still out of reach");
        assertInstanceOf(ActionIntent.Swing.class, here.get(0));
    }

    @Test
    void missChance_deterministicWhiff() {
        // missChance 1.0: an otherwise-valid hit is deliberately whiffed every time.
        Perception p = perception(selfAtOrigin(), targetAt(0.0, 64.0, 2.0, Vec3d.ZERO), 0);
        List<ActionIntent> out = new ArrayList<>();

        controller.consider(p, 0.0f, 3.0, 30.0, false, 1.0, primedClock(), FIRE_AT, mem(), out);

        assertEquals(1, out.size(), "a forced miss still swings the arm");
        assertInstanceOf(ActionIntent.Swing.class, out.get(0));
    }
}

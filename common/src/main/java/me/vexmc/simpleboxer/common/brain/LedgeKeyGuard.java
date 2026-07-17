package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * The last line of the ledge guard: validates the direction the QUANTIZED keys
 * will actually realize, not the analog heading steering approved.
 *
 * <p>Steering probes its chosen candidate, but the motor can only press whole
 * keys — {@link MotorQuantizer}'s deadband turns a heading with a small
 * ledge-ward component (anything in {@code (0.35, 0.707)}) into a full diagonal
 * whose realized direction carries {@code cos 45° ≈ 0.707} of it, nearly double
 * what the ledge probe checked; under sprint momentum that walks a ledge-averse
 * goal over a lip steering explicitly refused. Re-probing the realized key
 * direction closes that gap: a key pulling over the lip is RELEASED (never
 * fabricated), keeping the safe axis when one exists and holding position when
 * none does. A vanilla client walks off edges only when its player chooses to —
 * this guard is that choice, upstream of the move input like every brain layer.</p>
 */
final class LedgeKeyGuard {

    private LedgeKeyGuard() {}

    /**
     * Mask ledge-ward keys out of {@code input} for a ledge-averse goal: the
     * input comes back unchanged when its realized direction keeps ground
     * within {@link ContextSteering#LEDGE_MAX_DROP}; otherwise the pressed
     * single-key alternative truest to {@code heading} that stays grounded, or
     * all movement keys released when neither is safe (jump/sprint/sneak pass
     * through untouched either way).
     */
    static @NotNull MoveInput mask(@NotNull MoveInput input, @NotNull MoveHeading heading,
            float aimYawDeg, @NotNull CollisionView world, @NotNull Box box) {
        double forward = input.forward();
        double strafe = input.strafe();
        if (forward == 0.0 && strafe == 0.0) {
            return input;
        }
        if (!ledgeward(world, box, forward, strafe, aimYawDeg)) {
            return input;
        }
        Vec3d want = heading.dirWorld();
        MoveInput best = null;
        double bestDot = Double.NEGATIVE_INFINITY;
        if (forward != 0.0 && !ledgeward(world, box, forward, 0.0, aimYawDeg)) {
            best = new MoveInput(forward, 0.0, input.jump(), input.sprint(), input.sneak());
            bestDot = realized(forward, 0.0, aimYawDeg).horizontalNormalized().dot(want);
        }
        if (strafe != 0.0 && !ledgeward(world, box, 0.0, strafe, aimYawDeg)
                && realized(0.0, strafe, aimYawDeg).horizontalNormalized().dot(want) > bestDot) {
            best = new MoveInput(0.0, strafe, input.jump(), input.sprint(), input.sneak());
        }
        return best != null ? best
                : new MoveInput(0.0, 0.0, input.jump(), input.sprint(), input.sneak());
    }

    /** Whether these keys' realized world direction steps off into a deep drop. */
    private static boolean ledgeward(CollisionView world, Box box,
            double forward, double strafe, float aimYawDeg) {
        return NavGeometry.ledgeAhead(world, box, realized(forward, strafe, aimYawDeg),
                NavGeometry.LOOK_AHEAD, ContextSteering.LEDGE_MAX_DROP);
    }

    /** {@code ClientPhysics.accelerate}'s rotation: the world direction these keys drive. */
    private static Vec3d realized(double forward, double strafe, float aimYawDeg) {
        double yaw = Math.toRadians(aimYawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Vec3d(strafe * cos - forward * sin, 0.0, forward * cos + strafe * sin);
    }
}

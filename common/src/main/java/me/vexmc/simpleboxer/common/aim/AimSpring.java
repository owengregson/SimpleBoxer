package me.vexmc.simpleboxer.common.aim;

import org.jetbrains.annotations.NotNull;

/**
 * The crosshair: yaw/pitch chase a target through a discrete spring-damper.
 * Per axis, per tick: {@code vel = vel × damping + wrappedError × stiffness},
 * clamped to ±maxVelocity, then integrated. Yaw errors wrap to the short way
 * around; pitch clamps to ±90°.
 */
public final class AimSpring {

    private AimParams params;
    private float yaw;
    private float pitch;
    private double yawVelocity;
    private double pitchVelocity;

    public AimSpring(@NotNull AimParams params, float initialYaw, float initialPitch) {
        this.params = params;
        this.yaw = wrapDegrees(initialYaw);
        this.pitch = clampPitch(initialPitch);
    }

    public void retune(@NotNull AimParams newParams) {
        this.params = newParams;
    }

    /** One tick of pursuit toward the target angles. */
    public void step(float targetYaw, float targetPitch) {
        double yawError = wrapDegrees(targetYaw - yaw);
        yawVelocity = clamp(yawVelocity * params.damping() + yawError * params.stiffness(),
                params.maxVelocity());
        yaw = wrapDegrees((float) (yaw + yawVelocity));

        double pitchError = clampPitch(targetPitch) - pitch;
        pitchVelocity = clamp(pitchVelocity * params.damping() + pitchError * params.stiffness(),
                params.maxVelocity());
        pitch = clampPitch((float) (pitch + pitchVelocity));
    }

    /** Hard-snap (teleports, spawns) — also kills momentum. */
    public void snapTo(float newYaw, float newPitch) {
        this.yaw = wrapDegrees(newYaw);
        this.pitch = clampPitch(newPitch);
        this.yawVelocity = 0.0;
        this.pitchVelocity = 0.0;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** Remaining angular error to the target's yaw, short way, degrees. */
    public double yawErrorTo(float targetYaw) {
        return Math.abs(wrapDegrees(targetYaw - yaw));
    }

    private static double clamp(double velocity, double max) {
        return Math.max(-max, Math.min(max, velocity));
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}

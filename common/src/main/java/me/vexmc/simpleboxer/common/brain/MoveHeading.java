package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * A collision-aware world-space heading produced by context steering: the
 * cleanest direction toward the goal that also slides along walls and avoids
 * ledges. {@code nearLedge} warns the motor to ease off (a fleeing boxer must
 * not sprint off an edge). {@code speedScale} in [0,1] lets a goal ask for a
 * softer pace, which the motor expresses by DUTY-CYCLING a digital forward, not
 * by sending a fractional impulse.
 */
public record MoveHeading(@NotNull Vec3d dirWorld, boolean nearLedge, double speedScale) {

    public static final MoveHeading STILL = new MoveHeading(Vec3d.ZERO, false, 0.0);

    public MoveHeading(@NotNull Vec3d dirWorld) {
        this(dirWorld, false, 1.0);
    }

    public boolean isStill() {
        return dirWorld.horizontalDistanceSqr() < 1.0E-8;
    }
}

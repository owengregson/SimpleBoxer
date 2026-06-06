package me.vexmc.simpleboxer.common.physics;

/**
 * One tick of held keys, exactly as the vanilla keyboard layer models them:
 * impulses in [-1, 1] (forward = W, strafe = A positive / D negative —
 * vanilla's left-positive convention), plus the jump/sprint/sneak states.
 */
public record MoveInput(double forward, double strafe, boolean jump, boolean sprint, boolean sneak) {

    public static final MoveInput IDLE = new MoveInput(0.0, 0.0, false, false, false);

    public static MoveInput sprintForward() {
        return new MoveInput(1.0, 0.0, false, true, false);
    }

    public static MoveInput walkForward() {
        return new MoveInput(1.0, 0.0, false, false, false);
    }
}

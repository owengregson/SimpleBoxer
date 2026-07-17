package me.vexmc.simpleboxer.common.physics;

/**
 * One tick of held keys, exactly as the vanilla keyboard layer models them:
 * impulses in [-1, 1] (forward = W, strafe = A positive / D negative —
 * vanilla's left-positive convention), plus the jump/sprint/sneak states and
 * whether the client is mid item-use (blocking, eating, drawing) — the state
 * {@code LocalPlayer.aiStep} multiplies both impulses by 0.2 for.
 */
public record MoveInput(double forward, double strafe, boolean jump, boolean sprint, boolean sneak,
        boolean usingItem) {

    public static final MoveInput IDLE = new MoveInput(0.0, 0.0, false, false, false);

    /** The common not-using shape; item use opts in via {@link #withUsingItem}. */
    public MoveInput(double forward, double strafe, boolean jump, boolean sprint, boolean sneak) {
        this(forward, strafe, jump, sprint, sneak, false);
    }

    /** The same keys with the item-use state stamped on. */
    public MoveInput withUsingItem(boolean using) {
        return using == usingItem ? this
                : new MoveInput(forward, strafe, jump, sprint, sneak, using);
    }

    public static MoveInput sprintForward() {
        return new MoveInput(1.0, 0.0, false, true, false);
    }

    public static MoveInput walkForward() {
        return new MoveInput(1.0, 0.0, false, false, false);
    }
}

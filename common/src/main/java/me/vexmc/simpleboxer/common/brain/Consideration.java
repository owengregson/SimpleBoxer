package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * One normalized [0,1] situational factor a {@link Goal} weighs — "how low is my
 * health", "how close is the target", "how hard is the opponent tracking me",
 * "am I blocked". A goal's utility is the weighted product of its considerations,
 * so each is a single, independently-testable question about the
 * {@link Perception}. Pure; the literal "variable weight per situation" surface.
 */
@FunctionalInterface
public interface Consideration {

    /** The factor's value for this tick's perception, in [0,1]. */
    double eval(@NotNull Perception perception);

    /** Compose with a response curve: {@code raw -> curve(raw)}. */
    default @NotNull Consideration shaped(@NotNull ResponseCurve curve) {
        return perception -> curve.applyClamped(eval(perception));
    }

    static @NotNull Consideration constant(double value) {
        return perception -> value;
    }
}

package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * Shapes a normalized input in [0,1] into a normalized output in [0,1] — the
 * response curve half of a utility {@link Consideration}. Pure and total; the
 * factory methods cover the shapes the goal set needs. Inputs are clamped to
 * [0,1] so a caller never has to pre-clamp.
 */
@FunctionalInterface
public interface ResponseCurve {

    double apply(double x);

    default double applyClamped(double x) {
        return apply(Math.max(0.0, Math.min(1.0, x)));
    }

    /** Identity: y = x. */
    static @NotNull ResponseCurve linear() {
        return x -> x;
    }

    /** y = 1 - x (higher input, lower score). */
    static @NotNull ResponseCurve inverse() {
        return x -> 1.0 - x;
    }

    /** y = x^k (k>1 pushes low inputs down; k<1 lifts them up). */
    static @NotNull ResponseCurve power(double k) {
        return x -> Math.pow(x, k);
    }

    /** Logistic S-curve centered at {@code mid} with steepness {@code k}. */
    static @NotNull ResponseCurve logistic(double mid, double k) {
        return x -> 1.0 / (1.0 + Math.exp(-k * (x - mid)));
    }

    /**
     * 1 inside [{@code lo},{@code hi}] and ramping to 0 over a {@code fade}
     * margin on each side — a soft band, e.g. "is the target in rod range".
     */
    static @NotNull ResponseCurve band(double lo, double hi, double fade) {
        return x -> {
            if (x >= lo && x <= hi) {
                return 1.0;
            }
            if (x < lo) {
                return fade <= 0 ? 0.0 : Math.max(0.0, 1.0 - (lo - x) / fade);
            }
            return fade <= 0 ? 0.0 : Math.max(0.0, 1.0 - (x - hi) / fade);
        };
    }

    /** A hard threshold: 1 at/above {@code t}, else 0. */
    static @NotNull ResponseCurve step(double t) {
        return x -> x >= t ? 1.0 : 0.0;
    }
}

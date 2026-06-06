package me.vexmc.simpleboxer.common.combat;

import java.util.Random;
import org.jetbrains.annotations.NotNull;

/**
 * The clicking finger: emits clicks at a target CPS with human jitter.
 * Intervals are drawn around 1000/cps ms, each scaled by a uniform factor in
 * [1 − jitter, 1 + jitter] — the long-run rate stays cps while individual
 * gaps wobble. Deterministic from the seed: identical boxers click
 * identically (determinism is a feature in a test fixture).
 */
public final class ClickScheduler {

    private final Random random;
    private double cps;
    private double jitter;
    private long nextClickAtMs = Long.MIN_VALUE;

    public ClickScheduler(double cps, double jitter, long seed) {
        this.random = new Random(seed);
        retune(cps, jitter);
    }

    public void retune(double newCps, double newJitter) {
        if (newCps < 0.0 || newCps > 50.0) {
            throw new IllegalArgumentException("cps must be in [0,50]: " + newCps);
        }
        if (newJitter < 0.0 || newJitter > 0.9) {
            throw new IllegalArgumentException("jitter must be in [0,0.9]: " + newJitter);
        }
        this.cps = newCps;
        this.jitter = newJitter;
    }

    public double cps() {
        return cps;
    }

    /**
     * Whether a click fires in the tick ending at {@code nowMs}. At most one
     * per call — above 20 CPS the schedule saturates at one click per tick,
     * exactly like a real client's once-per-frame swing cadence at 20 TPS.
     */
    public boolean shouldClick(long nowMs) {
        if (cps <= 0.0) {
            return false;
        }
        if (nextClickAtMs == Long.MIN_VALUE) {
            nextClickAtMs = nowMs + nextInterval();
            return false;
        }
        if (nowMs < nextClickAtMs) {
            return false;
        }
        // Catch up from the SCHEDULED time, not from now: late ticks must not
        // depress the long-run rate.
        nextClickAtMs += nextInterval();
        if (nextClickAtMs <= nowMs) {
            nextClickAtMs = nowMs + nextInterval();
        }
        return true;
    }

    /** Forget the schedule (pause/resume, target swaps). */
    public void reset() {
        this.nextClickAtMs = Long.MIN_VALUE;
    }

    private long nextInterval() {
        double base = 1000.0 / cps;
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter;
        return Math.max(1L, Math.round(base * factor));
    }

    @Override
    public @NotNull String toString() {
        return "ClickScheduler[cps=" + cps + ", jitter=" + jitter + "]";
    }
}

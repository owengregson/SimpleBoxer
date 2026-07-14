package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The utility selector at the top of the brain: every tick it scores each
 * {@link Goal}, applies commit/dwell hysteresis so borderline scores don't
 * flip-flop, honours an exclusive survive/heal latch, and hands the winner the
 * {@link Perception} so it can emit an {@link Intent}.
 *
 * <p>Three forces shape the choice, in order of authority:
 * <ol>
 *   <li><b>Exclusive latch</b> — a goal that returns {@code exclusive(p)} while
 *       positive HARD-SEIZES the tick; nothing outscores a survive/heal reflex.</li>
 *   <li><b>Dwell</b> — a freshly-won incumbent keeps control for its
 *       {@code minDwellTicks()} even against a slightly higher rival, so we don't
 *       thrash strategies mid-commitment. Only a rival that beats it by
 *       {@link #LARGE_MARGIN} (or the incumbent collapsing to zero) breaks it.</li>
 *   <li><b>Commit bonus</b> — a persistent thumb on the scale for the incumbent,
 *       so once dwell is served we still bias toward continuity, not raw score.</li>
 * </ol>
 *
 * <p>Pure and deterministic: the arbiter reads nothing but the goals, the
 * {@link Perception}, and the owning-thread {@link BrainMemory} scratchpad it
 * mutates for hysteresis. {@code decide} is never invoked on a zero-utility goal
 * — the only fallback is the trivial {@link #IDLE_GOAL}.
 */
public final class Arbiter {

    /**
     * How far a challenger must out-score a still-dwelling incumbent to break the
     * dwell early. Utilities across goals are authored on a comparable 0..~1
     * scale, so half a point is a decisively-better option, not mere noise.
     */
    public static final double LARGE_MARGIN = 0.5;

    /** The do-nothing fallback: wins only when every real goal wants nothing. */
    private static final Goal IDLE_GOAL = new Goal() {
        @Override
        public @NotNull String id() {
            return "idle";
        }

        @Override
        public double utility(@NotNull Perception perception) {
            return 0.0;
        }

        @Override
        public @NotNull Intent decide(@NotNull Perception perception, @NotNull BrainMemory memory) {
            return Intent.IDLE;
        }
    };

    private final @NotNull List<Goal> goals;

    public Arbiter(@NotNull List<Goal> goals) {
        this.goals = List.copyOf(goals);
    }

    /** The chosen behavior and the concrete intent it produced this tick. */
    public record Result(@NotNull Goal goal, @NotNull Intent intent) {}

    /**
     * Score the field, apply the latch/dwell/commit rules, then let the winner
     * decide. Mutates {@code mem.incumbentGoal}/{@code mem.dwellTicks} to record
     * the choice (dwell resets to 0 on a switch, increments when held).
     */
    public @NotNull Result select(@NotNull Perception p, @NotNull BrainMemory mem) {
        final int n = goals.size();

        // Score every goal exactly once — utility() may be non-trivial and can be
        // observed for side effects in tests, so it must fire once per tick.
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            raw[i] = Math.max(0.0, goals.get(i).utility(p));
        }

        // The incumbent is only "alive" if it still exists and still wants something;
        // a collapsed-to-zero incumbent forfeits both dwell protection and its bonus.
        int incIdx = -1;
        String incId = mem.incumbentGoal;
        if (incId != null) {
            for (int i = 0; i < n; i++) {
                if (raw[i] > 0.0 && incId.equals(goals.get(i).id())) {
                    incIdx = i;
                    break;
                }
            }
        }

        // Effective score = raw utility + commit bonus, the bonus only for the
        // living incumbent (its persistent bias toward continuity).
        double[] eff = new double[n];
        for (int i = 0; i < n; i++) {
            eff[i] = raw[i] + (i == incIdx ? goals.get(i).commitBonus() : 0.0);
        }

        // 1) Exclusive latch: highest-scoring positive exclusive goal seizes the
        //    tick outright, regardless of any higher non-exclusive score.
        int winner = -1;
        for (int i = 0; i < n; i++) {
            if (raw[i] > 0.0 && goals.get(i).exclusive(p)) {
                if (winner < 0 || eff[i] > eff[winner]) {
                    winner = i;
                }
            }
        }

        if (winner < 0) {
            // 2) No latch — find the best positive contender by effective score.
            int best = argMaxPositive(raw, eff, -1);
            if (best < 0) {
                // Every real goal wants nothing: fall back to idle, without ever
                // calling decide() on a zero-utility goal.
                return finalize(IDLE_GOAL, p, mem);
            }

            if (incIdx >= 0 && mem.dwellTicks < goals.get(incIdx).minDwellTicks()) {
                // 3) Dwell: the incumbent has not served its minimum yet. It holds
                //    unless a rival beats it by a large margin.
                int challenger = argMaxPositive(raw, eff, incIdx);
                winner = (challenger >= 0 && eff[challenger] >= eff[incIdx] + LARGE_MARGIN)
                        ? challenger
                        : incIdx;
            } else {
                // Dwell served (or no incumbent): pure effective-score contest,
                // still tilted toward the incumbent by its commit bonus.
                winner = best;
            }
        }

        return finalize(goals.get(winner), p, mem);
    }

    /**
     * Index of the highest effective-score goal with positive raw utility, or -1
     * if none. {@code skip} excludes one index (used to find the best challenger
     * that is NOT the incumbent). Ties resolve to the lower index for determinism.
     */
    private static int argMaxPositive(double[] raw, double[] eff, int skip) {
        int best = -1;
        for (int i = 0; i < raw.length; i++) {
            if (i == skip || raw[i] <= 0.0) {
                continue;
            }
            if (best < 0 || eff[i] > eff[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Record the choice into memory (dwell bookkeeping) and run the winner. */
    private static @NotNull Result finalize(@NotNull Goal winner, @NotNull Perception p, @NotNull BrainMemory mem) {
        if (winner.id().equals(mem.incumbentGoal)) {
            mem.dwellTicks++;
        } else {
            mem.incumbentGoal = winner.id();
            mem.dwellTicks = 0;
        }
        return new Result(winner, winner.decide(p, mem));
    }
}

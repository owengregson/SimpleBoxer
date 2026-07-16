package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PotHeal (feature 15) — a MORTAL, low-health boxer disengages, retreats, and
 * splashes instant-health potions at its own feet until it recovers, then
 * re-engages. An EXCLUSIVE survival latch: once it fires it hard-seizes control
 * so ordinary {@code Engage} cannot pre-empt a healing boxer.
 *
 * <p>Trigger/release hysteresis lives in the {@link #latched} instance field
 * rather than {@link BrainMemory}, because {@link #utility(Perception)} — which
 * must decide whether to seize BEFORE the goal wins and {@code decide} ever
 * runs — is handed only a {@link Perception}, not the memory. {@code utility}
 * therefore updates the latch (health crossing {@code triggerHealth} arms it,
 * health reaching {@code resumeHealth} disarms it); the multi-tick FSM counters
 * live in {@code mem.ints("potHeal", 7) = {phase, potsThrown, waitTimer, gaveUp,
 * retreatTicks, launchBase, failStreak}}.</p>
 *
 * <p>The {@code splashCap} is an EPISODE budget of CONFIRMED throws, not of
 * use-item attempts: each throw first records the perception's cumulative
 * {@code potsLaunched} count, and only a wait window that ends with the count
 * advanced — the server actually spawned our ThrownPotion — increments
 * {@code potsThrown}. A swallowed use-item (spam-gated, wrong slot, empty hand)
 * is retried instead of silently burning the budget, and
 * {@link #THROW_FAIL_CAP} consecutive non-launches read as "this server cannot
 * throw" and end the episode through the give-up path rather than looping
 * forever.</p>
 *
 * <p>If the boxer spends its whole cap without recovering, {@code decide} sets
 * the durable {@code gaveUp} flag, and {@code utility} reads it (via the
 * {@code mem} it caches on the first {@code decide}) to stay silent —
 * re-engaging rather than looping another full batch — until health genuinely
 * climbs back to {@code resumeHealth}, where {@code utility} clears the flag
 * and a fresh trigger re-arms the budget.</p>
 */
public final class PotHealGoal implements Goal {

    /** Utility ceiling when the boxer is near death — must out-score ordinary engage. */
    public static final double MAX_UTILITY = 0.95;
    /**
     * A positive floor kept while latched even as health climbs back toward
     * {@code resumeHealth}, so the latch keeps winning arbitration through the
     * whole recovery (above {@code EngageGoal.BASE_UTILITY} of 0.5).
     */
    public static final double HOLD_FLOOR = 0.6;

    /** Distance (blocks) the boxer opens up before it starts drinking. */
    private static final double RETREAT_DISTANCE = 4.5;
    /** Ticks to weave in the splash cloud before re-evaluating. */
    private static final int WAIT_TICKS = 10;
    /**
     * Shortest heal-juke hold (ticks) on one side; holds walk a side-balanced
     * palindrome over {@link #JUKE_HOLD_SPAN} lengths (2,3,4,5,5,4,3,2) from a
     * seed-drawn phase, so the weave never settles into a fixed metronome yet
     * stays deterministic for a given boxer (see {@code healJuke}).
     */
    private static final int JUKE_MIN_HOLD = 2;
    /** How many distinct hold lengths the juke cycles through. */
    private static final int JUKE_HOLD_SPAN = 4;
    /**
     * Radial drift blended under the lateral weave (fraction of the tangent),
     * signed by the kite ring below: opening while too close, closing while too
     * far. Each pot is thrown at the boxer's CURRENT feet, so the splash still
     * lands on it wherever the drift has carried it.
     */
    private static final double JUKE_DRIFT = 0.35;
    /**
     * Inner edge of the heal-kite ring: below it the weave keeps opening
     * distance (the same gap the phase-0 retreat sprints for).
     */
    private static final double KITE_NEAR = RETREAT_DISTANCE;
    /**
     * Outer edge of the heal-kite ring: beyond it the drift REVERSES toward the
     * target. Back-to-back episodes skip the phase-0 gate once the gap is open,
     * so an unconditional away-drift would compound episode over episode and
     * march a repeatedly-hurt healer monotonically off any finite arena — a
     * fatal ledge fall mid-heal (the tester's pot-budget case pins this on a
     * floating pad). A real player kites a bounded circle around the threat;
     * so does this ring.
     */
    private static final double KITE_FAR = RETREAT_DISTANCE + 3.0;
    /**
     * How far ahead of the feet (blocks, along the flee heading) the throw
     * aims. {@code Brain.applyFacing} computes pitch against the 1.62 eye
     * height: atan2(1.62, 0.3) ≈ 79.5° down — steep enough that a splash pot
     * (launched 20° above the crosshair at speed 0.5) lands at a sprinting
     * thrower's feet — while the non-zero horizontal offset keeps yaw pinned
     * to the flee heading: a zero offset would degenerate to atan2(0, 0) = 0
     * and pin the crosshair to world-south for the whole heal.
     */
    private static final double THROW_AIM_AHEAD = 0.3;
    /**
     * Consecutive throws the server never confirmed (no ThrownPotion spawned
     * inside the wait window) before the episode ends through the give-up path
     * — a capability failure, not a supply one, must not retry forever.
     */
    private static final int THROW_FAIL_CAP = 3;
    /**
     * Hard ceiling on time spent in the phase-0 retreat before drinking anyway. A
     * same-speed chaser keeps the gap constant, so the {@link #RETREAT_DISTANCE}
     * gate alone can never become true — drinking under pressure beats an infinite
     * backpedal.
     */
    public static final int RETREAT_TICK_CAP = 40;

    private static final String STATE_ID = "potHeal";
    private static final int PHASE = 0;
    /** CONFIRMED throws this episode — only advanced by an observed launch. */
    private static final int POTS_THROWN = 1;
    private static final int WAIT_TIMER = 2;
    /** Durable "spent the cap without recovering" latch — persists across phase cycles. */
    private static final int GAVE_UP = 3;
    /** How many consecutive ticks the boxer has been stuck in the phase-0 retreat. */
    private static final int RETREAT_TICKS = 4;
    /** The perception's cumulative launch count recorded just before a use-item. */
    private static final int LAUNCH_BASE = 5;
    /** Consecutive unconfirmed throws (reset by any confirmed launch). */
    private static final int FAIL_STREAK = 6;
    private static final int STATE_SIZE = 7;

    private final Supplier<BoxerSettings> settings;

    /** Hysteresis latch: armed below trigger, released at/above resume. */
    private boolean latched;

    /**
     * The owning boxer's scratchpad, captured on the first {@code decide}. Lets the
     * perception-only {@code utility} read (and clear on recovery) the mem-resident
     * give-up flag. Null until the goal has decided at least once (treated as "not
     * given up").
     */
    private @Nullable BrainMemory owned;

    public PotHealGoal(@NotNull Supplier<BoxerSettings> settings) {
        this.settings = settings;
    }

    @Override
    public @NotNull String id() {
        return "potHeal";
    }

    @Override
    public double utility(@NotNull Perception p) {
        BoxerSettings s = settings.get();
        BoxerSettings.SelfHeal heal = s.selfHeal();

        // Eligibility gate: a mortal, self-heal-enabled boxer that actually has
        // pots and someone to disengage from. Anything else fully disarms.
        if (s.invincible() || !heal.enabled() || !p.inv().hasPots() || !p.hasTarget()) {
            latched = false;
            setGaveUp(false);
            return 0.0;
        }

        double hp20 = p.self().healthPct() * 20.0;

        // Hysteresis + episode-wide splash cap. Arm when we sink to the trigger,
        // disarm only once we have climbed back to the resume line; between the two,
        // hold the last state so we do not drop out at trigger + epsilon.
        if (hp20 >= heal.resumeHealth()) {
            // Genuine recovery: release the heal latch AND clear the give-up latch,
            // so a future health drop starts a fresh episode with a full pot budget.
            latched = false;
            setGaveUp(false);
        } else if (hp20 <= heal.triggerHealth()) {
            // At/below the trigger. Re-arm ONLY if we have not already spent this
            // episode's whole splashCap without recovering: that give-up latch stays
            // durable (utility 0) until health actually climbs back to resume, so the
            // boxer re-engages instead of looping full pot batches forever.
            if (!gaveUp()) {
                if (!latched) {
                    // A genuine healthy -> trigger crossing: reset the episode's pot
                    // budget and fail streak. (Only here — never on a give-up.)
                    resetEpisode();
                }
                latched = true;
            }
        }

        if (gaveUp() || !latched) {
            return 0.0;
        }

        // Ramp toward MAX_UTILITY as health falls below the trigger; never below
        // the hold floor while latched, so recovery keeps the latch in control.
        double denom = Math.max(heal.triggerHealth(), 1.0E-6);
        double frac = (heal.triggerHealth() - hp20) / denom;
        if (frac < 0.0) {
            frac = 0.0;
        } else if (frac > 1.0) {
            frac = 1.0;
        }
        return Math.max(HOLD_FLOOR, MAX_UTILITY * frac);
    }

    /** Whether the boxer spent its whole splashCap this episode without recovering. */
    private boolean gaveUp() {
        return owned != null && owned.ints(STATE_ID, STATE_SIZE)[GAVE_UP] != 0;
    }

    private void setGaveUp(boolean value) {
        if (owned != null) {
            owned.ints(STATE_ID, STATE_SIZE)[GAVE_UP] = value ? 1 : 0;
        }
    }

    /** A fresh healthy -> trigger crossing: a full budget and a clean fail streak. */
    private void resetEpisode() {
        if (owned != null) {
            int[] st = owned.ints(STATE_ID, STATE_SIZE);
            st[POTS_THROWN] = 0;
            st[FAIL_STREAK] = 0;
        }
    }

    /**
     * A side-to-side weave (tangent to the flee direction) blended with a
     * ring-banded radial drift, for the throw/wait phases: the boxer keeps
     * moving — fluid and hard to punish — while each side is held a varied 2–5
     * ticks so the weave never reads as a metronome. Deterministic: one
     * {@code mem.rng} draw ever (the cycle phase), then a plain counter walk.
     *
     * <p>The holds walk the PALINDROME 2,3,4,5,5,4,3,2 — the shape matters: a
     * plain forward walk of the even-length cycle hands one side {2,4} = 6 and
     * the other {3,5} = 8 of every 14 ticks (sides flip per hold, and the even
     * period pins each side to the same two lengths forever), a hidden constant
     * lateral drift. The palindrome gives BOTH sides 14 of every 28 ticks at
     * any seed phase, so the weave is drift-free over its period.</p>
     */
    private static @NotNull Vec3d healJuke(@NotNull Vec3d awayDir, double distance,
            @NotNull BrainMemory mem) {
        Vec3d flat = awayDir.horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            flat = new Vec3d(1.0, 0.0, 0.0);
        }
        // {ticksLeft, side, cycle}: the side flips when its hold expires, and the
        // hold length walks the palindrome from a seed-drawn phase (cycle==0 means
        // "not seeded yet" — the stored value is 1-based).
        int[] w = mem.ints("potJuke", 3);
        if (w[0] <= 0) {
            w[2] = w[2] == 0 ? 1 + mem.rng.nextInt(2 * JUKE_HOLD_SPAN) : w[2] + 1;
            int step = (w[2] - 1) % (2 * JUKE_HOLD_SPAN);
            int offset = step < JUKE_HOLD_SPAN ? step : 2 * JUKE_HOLD_SPAN - 1 - step;
            w[0] = JUKE_MIN_HOLD + offset;
            w[1] ^= 1;
        }
        w[0]--;
        Vec3d tangent = w[1] == 0 ? new Vec3d(-flat.z(), 0.0, flat.x())
                : new Vec3d(flat.z(), 0.0, -flat.x());
        // The kite ring: open below it, orbit inside it, close back above it.
        double radial = distance < KITE_NEAR ? JUKE_DRIFT
                : distance > KITE_FAR ? -JUKE_DRIFT : 0.0;
        if (radial == 0.0) {
            return tangent; // already unit length
        }
        return tangent.add(flat.scale(radial)).normalized();
    }

    @Override
    public boolean exclusive(@NotNull Perception p) {
        // Hard-seize whenever we would score: a healing boxer must not be
        // pre-empted by Engage mid-retreat.
        return utility(p) > 0.0;
    }

    @Override
    public boolean suppressesAttack() {
        return true; // a retreating, drinking boxer holds its fire
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        this.owned = mem;
        Perception.TargetState t = p.target();
        if (t == null) {
            return Intent.IDLE;
        }
        BoxerSettings s = settings.get();
        Perception.SelfState self = p.self();

        // Horizontal unit vector FROM the target TO the boxer: the flee heading.
        Vec3d awayDir = new Vec3d(self.x() - t.x(), 0.0, self.z() - t.z()).horizontalNormalized();
        if (awayDir.lengthSqr() < 1.0E-8) {
            awayDir = new Vec3d(1.0, 0.0, 0.0); // degenerate overlap: pick a stable heading
        }

        // The throw aim: a ground point just ahead along the flee heading. Yaw
        // stays on the retreat line (no atan2(0,0) whip) and pitch lands ~79.5°
        // (see THROW_AIM_AHEAD) — the pot still splashes at our feet on the run.
        Intent.FacingIntent throwAim = Intent.FacingIntent.aimAt(
                self.x() + awayDir.x() * THROW_AIM_AHEAD,
                self.y(),
                self.z() + awayDir.z() * THROW_AIM_AHEAD);

        // FSM state: {phase, potsThrown, waitTimer, gaveUp, retreatTicks,
        // launchBase, failStreak}.
        int[] st = mem.ints(STATE_ID, STATE_SIZE);
        int phase = st[PHASE];

        switch (phase) {
            case 0: // create space — sprint straight away until we have room
                // Advance once we have distance OR the retreat has run long enough: a
                // same-speed chaser pins the gap, so waiting on distance alone would
                // deadlock here forever (exclusive + suppressesAttack) and never heal.
                st[RETREAT_TICKS] = st[RETREAT_TICKS] + 1;
                if (t.distance() > RETREAT_DISTANCE || st[RETREAT_TICKS] >= RETREAT_TICK_CAP) {
                    st[PHASE] = 1;
                    st[RETREAT_TICKS] = 0;
                }
                return new Intent(awayDir, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.none(), true, Intent.JumpHint.NONE);

            case 1: // swap to the pot slot, keep sprinting away, and START pitching
                    // down a tick EARLY: the aim spring needs a tick to reach the
                    // throw angle, so the pot leaves on-angle next tick instead of
                    // sailing out shallow.
                st[PHASE] = 2;
                return new Intent(awayDir, throwAim,
                        Intent.ActionIntent.selectSlot(s.items().potSlot()),
                        true, Intent.JumpHint.NONE);

            case 2: // throw on the run, weaving: record the launch baseline BEFORE
                    // the use, so phase 3 can tell a server-confirmed launch from a
                    // silently swallowed use-item.
                st[LAUNCH_BASE] = p.combat().potsLaunched();
                st[WAIT_TIMER] = WAIT_TICKS;
                st[PHASE] = 3;
                return new Intent(healJuke(awayDir, t.distance(), mem), throwAim,
                        Intent.ActionIntent.startUse(true), true, Intent.JumpHint.NONE);

            case 3: // weave through the splash while the launch confirms; when the
                    // window settles: count ONLY a confirmed throw, then recover,
                    // retry, repeat, or give up.
                st[WAIT_TIMER] = st[WAIT_TIMER] - 1;
                if (st[WAIT_TIMER] <= 0) {
                    boolean launched = p.combat().potsLaunched() > st[LAUNCH_BASE];
                    if (launched) {
                        st[POTS_THROWN] = st[POTS_THROWN] + 1;
                        st[FAIL_STREAK] = 0;
                    } else {
                        // No ThrownPotion ever spawned for our use-item (dropped,
                        // wrong slot, empty hand): the budget is NOT charged — retry,
                        // bounded by the fail cap.
                        st[FAIL_STREAK] = st[FAIL_STREAK] + 1;
                    }
                    double hp20 = self.healthPct() * 20.0;
                    boolean recovered = hp20 >= s.selfHeal().resumeHealth();
                    boolean capped = st[POTS_THROWN] >= s.selfHeal().splashCap();
                    boolean broken = st[FAIL_STREAK] >= THROW_FAIL_CAP;
                    st[PHASE] = (recovered || capped || broken) ? 4 : 1;
                }
                return new Intent(healJuke(awayDir, t.distance(), mem), throwAim,
                        Intent.ActionIntent.none(), true, Intent.JumpHint.NONE);

            default: { // 4: swap the weapon back, then either finish or give up
                double hp20 = self.healthPct() * 20.0;
                boolean recovered = hp20 >= s.selfHeal().resumeHealth();
                st[PHASE] = 0;
                st[WAIT_TIMER] = 0;
                st[RETREAT_TICKS] = 0;
                st[FAIL_STREAK] = 0;
                latched = false;
                if (recovered) {
                    // Genuine completion: clear the pot budget and the give-up latch.
                    st[POTS_THROWN] = 0;
                    st[GAVE_UP] = 0;
                } else {
                    // Cap spent (or throws kept failing) without recovering: give up
                    // durably. KEEP potsThrown (it is only reset on a fresh healthy ->
                    // trigger crossing) and set the give-up latch so utility stays 0
                    // until health reaches resume, instead of re-latching and throwing
                    // another full splashCap batch.
                    st[GAVE_UP] = 1;
                }
                return new Intent(Vec3d.ZERO, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.selectSlot(s.items().weaponSlot()),
                        false, Intent.JumpHint.NONE);
            }
        }
    }
}

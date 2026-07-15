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
 * live in {@code mem.ints("potHeal", 5) = {phase, potsThrown, waitTimer, gaveUp,
 * retreatTicks}}.</p>
 *
 * <p>The {@code splashCap} is an EPISODE budget, not a per-cycle one: if the boxer
 * spends its whole cap without recovering, {@code decide} sets the durable
 * {@code gaveUp} flag, and {@code utility} reads it (via the {@code mem} it caches
 * on the first {@code decide}) to stay silent — re-engaging rather than looping
 * another full batch — until health genuinely climbs back to {@code resumeHealth},
 * where {@code utility} clears the flag and a fresh trigger re-arms the budget.</p>
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
    /** How many ticks the heal-juke holds one side before flipping (keeps net drift small). */
    private static final int JUKE_FLIP_TICKS = 3;
    /**
     * Hard ceiling on time spent in the phase-0 retreat before drinking anyway. A
     * same-speed chaser keeps the gap constant, so the {@link #RETREAT_DISTANCE}
     * gate alone can never become true — drinking under pressure beats an infinite
     * backpedal.
     */
    public static final int RETREAT_TICK_CAP = 40;

    private static final String STATE_ID = "potHeal";
    private static final int PHASE = 0;
    private static final int POTS_THROWN = 1;
    private static final int WAIT_TIMER = 2;
    /** Durable "spent the cap without recovering" latch — persists across phase cycles. */
    private static final int GAVE_UP = 3;
    /** How many consecutive ticks the boxer has been stuck in the phase-0 retreat. */
    private static final int RETREAT_TICKS = 4;
    private static final int STATE_SIZE = 5;

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
                    // budget. (Only here — never on a give-up.)
                    resetPotsThrown();
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

    private void resetPotsThrown() {
        if (owned != null) {
            owned.ints(STATE_ID, STATE_SIZE)[POTS_THROWN] = 0;
        }
    }

    /**
     * A gentle side-to-side weave (tangent to the flee direction) for the throw/wait
     * phases: it keeps the boxer moving — fluid and hard to punish — while the short
     * flip cadence keeps its net drift inside the splash-heal cloud. Deterministic
     * (a plain scratch counter, no rng).
     */
    private static @NotNull Vec3d healJuke(@NotNull Vec3d awayDir, @NotNull BrainMemory mem) {
        Vec3d flat = awayDir.horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            flat = new Vec3d(1.0, 0.0, 0.0);
        }
        int[] w = mem.ints("potJuke", 1);
        boolean left = ((w[0]++ / JUKE_FLIP_TICKS) & 1) == 0;
        return left ? new Vec3d(-flat.z(), 0.0, flat.x())
                : new Vec3d(flat.z(), 0.0, -flat.x());
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

        Intent.FacingIntent feet = Intent.FacingIntent.aimAt(self.x(), self.y() - 0.5, self.z());

        // FSM state: {phase, potsThrown, waitTimer, gaveUp, retreatTicks}.
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

            case 1: // swap to the pot slot, keep backing off, and START aiming down a
                    // tick EARLY: the aim spring needs a tick to pitch to our feet, so
                    // facing them here means the pot actually lands at our feet next
                    // tick instead of sailing out shallow.
                st[PHASE] = 2;
                return new Intent(awayDir, feet,
                        Intent.ActionIntent.selectSlot(s.items().potSlot()),
                        true, Intent.JumpHint.NONE);

            case 2: // throw the splash pot at our feet while JUKING sideways — fluid and
                    // evasive, not a sitting duck; a lateral weave keeps us inside the
                    // heal cloud (splash range is generous) instead of standing still.
                st[POTS_THROWN] = st[POTS_THROWN] + 1;
                st[WAIT_TIMER] = WAIT_TICKS;
                st[PHASE] = 3;
                return new Intent(healJuke(awayDir, mem), feet,
                        Intent.ActionIntent.startUse(true), false, Intent.JumpHint.NONE);

            case 3: // weave in the settling cloud (never standing still); when it
                    // settles, recover, repeat, or give up.
                st[WAIT_TIMER] = st[WAIT_TIMER] - 1;
                if (st[WAIT_TIMER] <= 0) {
                    double hp20 = self.healthPct() * 20.0;
                    boolean recovered = hp20 >= s.selfHeal().resumeHealth();
                    boolean capped = st[POTS_THROWN] >= s.selfHeal().splashCap();
                    st[PHASE] = (recovered || capped) ? 4 : 1;
                }
                return new Intent(healJuke(awayDir, mem), feet,
                        Intent.ActionIntent.none(), false, Intent.JumpHint.NONE);

            default: { // 4: swap the weapon back, then either finish or give up
                double hp20 = self.healthPct() * 20.0;
                boolean recovered = hp20 >= s.selfHeal().resumeHealth();
                st[PHASE] = 0;
                st[WAIT_TIMER] = 0;
                st[RETREAT_TICKS] = 0;
                latched = false;
                if (recovered) {
                    // Genuine completion: clear the pot budget and the give-up latch.
                    st[POTS_THROWN] = 0;
                    st[GAVE_UP] = 0;
                } else {
                    // Cap spent without recovering: give up durably. KEEP potsThrown
                    // (it is only reset on a fresh healthy -> trigger crossing) and set
                    // the give-up latch so utility stays 0 until health reaches resume,
                    // instead of re-latching and throwing another full splashCap batch.
                    st[GAVE_UP] = 1;
                }
                return new Intent(Vec3d.ZERO, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.selectSlot(s.items().weaponSlot()),
                        false, Intent.JumpHint.NONE);
            }
        }
    }
}

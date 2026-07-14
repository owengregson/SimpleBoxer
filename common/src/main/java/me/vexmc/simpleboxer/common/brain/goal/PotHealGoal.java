package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

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
 * health reaching {@code resumeHealth} disarms it) but never touches
 * {@link BrainMemory}; the multi-tick FSM counters live in
 * {@code mem.ints("potHeal", 3)}.</p>
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
    /** Ticks to stand in the splash cloud before re-evaluating. */
    private static final int WAIT_TICKS = 10;

    private final Supplier<BoxerSettings> settings;

    /** Hysteresis latch: armed below trigger, released at/above resume. */
    private boolean latched;

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
            return 0.0;
        }

        double hp20 = p.self().healthPct() * 20.0;

        // Hysteresis: arm when we sink to the trigger, disarm only once we have
        // climbed back to the resume line. Between the two, hold the last state
        // so we do not drop out at trigger + epsilon.
        if (hp20 <= heal.triggerHealth()) {
            latched = true;
        } else if (hp20 >= heal.resumeHealth()) {
            latched = false;
        }

        if (!latched) {
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

        // FSM state: {phase, potsThrown, waitTimer}.
        int[] st = mem.ints("potHeal", 3);
        int phase = st[0];

        switch (phase) {
            case 0: // create space — sprint straight away until we have room
                if (t.distance() > RETREAT_DISTANCE) {
                    st[0] = 1;
                }
                return new Intent(awayDir, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.none(), true, Intent.JumpHint.NONE);

            case 1: // swap to the pot slot, keep backing off
                st[0] = 2;
                return new Intent(awayDir, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.selectSlot(s.items().potSlot()),
                        true, Intent.JumpHint.NONE);

            case 2: // throw the splash pot straight down at our own feet, standing still
                st[1] = st[1] + 1;       // potsThrown++
                st[2] = WAIT_TICKS;       // waitTimer
                st[0] = 3;
                return new Intent(Vec3d.ZERO, feet,
                        Intent.ActionIntent.startUse(true), false, Intent.JumpHint.NONE);

            case 3: // stand in the cloud; when it settles, recover-or-repeat
                st[2] = st[2] - 1;
                if (st[2] <= 0) {
                    double hp20 = self.healthPct() * 20.0;
                    boolean recovered = hp20 >= s.selfHeal().resumeHealth();
                    boolean capped = st[1] >= s.selfHeal().splashCap();
                    st[0] = (recovered || capped) ? 4 : 1;
                }
                return new Intent(Vec3d.ZERO, feet,
                        Intent.ActionIntent.none(), false, Intent.JumpHint.NONE);

            default: // 4: swap back to the weapon, reset, and release the latch
                st[0] = 0;
                st[1] = 0;
                st[2] = 0;
                latched = false;
                return new Intent(Vec3d.ZERO, Intent.FacingIntent.faceMove(),
                        Intent.ActionIntent.selectSlot(s.items().weaponSlot()),
                        false, Intent.JumpHint.NONE);
        }
    }
}

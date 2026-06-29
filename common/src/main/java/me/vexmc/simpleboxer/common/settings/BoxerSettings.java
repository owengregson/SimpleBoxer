package me.vexmc.simpleboxer.common.settings;

import me.vexmc.simpleboxer.common.aim.AimParams;
import org.jetbrains.annotations.NotNull;

/**
 * The complete behavior profile of one boxer — immutable, swapped by
 * reference (Mental's atomic-snapshot discipline). Defaults describe an
 * unhandicapped sparring partner: no artificial ping, crisp-but-human aim,
 * spam-click cadence, straight rush, no w-tap — every handicap and every
 * skill expression is an explicit opt-in from here.
 */
public record BoxerSettings(
        int pingMs,
        double cps,
        double clickJitter,
        @NotNull AimParams aim,
        double reach,
        double aimToleranceDegrees,
        @NotNull WTap wtap,
        @NotNull Movement movement,
        boolean invincible,
        boolean feedHunger) {

    /**
     * W-tap behavior: after a landed hit, release forward (dropping sprint)
     * for {@code releaseTicks}, starting {@code delayTicks} after the hit,
     * then re-press and re-sprint — the packet sequence a real w-tapper's
     * client emits, re-arming the sprint bonus for the next hit.
     */
    public record WTap(boolean enabled, int delayTicks, int releaseTicks) {

        public static final WTap OFF = new WTap(false, 1, 2);

        public WTap {
            if (delayTicks < 0 || delayTicks > 20) {
                throw new IllegalArgumentException("delayTicks must be in [0,20]: " + delayTicks);
            }
            if (releaseTicks < 1 || releaseTicks > 20) {
                throw new IllegalArgumentException("releaseTicks must be in [1,20]: " + releaseTicks);
            }
        }
    }

    /**
     * How the boxer closes distance. {@code stopDistance} is the range at
     * which the forward key releases; 0 — the default — NEVER releases: the
     * boxer holds W through its target and lets entity pushing resolve the
     * pocket, exactly like a real W-holding rusher. Easing off in close
     * drops sprint (vanilla needs forward impulse ≥ 0.8) and kills the
     * momentum that survives combos — a raised ring is strictly for
     * deliberate range-discipline sparring partners.
     */
    public record Movement(@NotNull Style style, double stopDistance, boolean sprint) {

        public enum Style {
            /** Sprint straight at the target. */
            RUSH,
            /** Close in, then circle the target at stop distance. */
            STRAFE_CIRCLE,
            /** Close in, then weave left-right on a short cadence. */
            STRAFE_WEAVE,
            /** Hold position; aim and attack only. */
            STAND
        }

        public static final Movement RUSH = new Movement(Style.RUSH, 0.0, true);

        public Movement {
            if (stopDistance < 0.0 || stopDistance > 6.0) {
                throw new IllegalArgumentException("stopDistance must be in [0,6]: " + stopDistance);
            }
        }
    }

    public static final BoxerSettings DEFAULTS = new BoxerSettings(
            0,
            8.0,
            0.3,
            AimParams.SHARP,
            3.0,
            10.0,
            WTap.OFF,
            Movement.RUSH,
            true,
            true);

    public BoxerSettings {
        if (pingMs < 0 || pingMs > 2000) {
            throw new IllegalArgumentException("pingMs must be in [0,2000]: " + pingMs);
        }
        if (reach < 0.5 || reach > 6.0) {
            throw new IllegalArgumentException("reach must be in [0.5,6]: " + reach);
        }
        if (aimToleranceDegrees < 0.0 || aimToleranceDegrees > 180.0) {
            throw new IllegalArgumentException(
                    "aimToleranceDegrees must be in [0,180]: " + aimToleranceDegrees);
        }
    }

    /* Wither-style copies for runtime tuning (/boxer set …). */

    public @NotNull BoxerSettings withPingMs(int newPingMs) {
        return new BoxerSettings(newPingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withCps(double newCps) {
        return new BoxerSettings(pingMs, newCps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withClickJitter(double newClickJitter) {
        return new BoxerSettings(pingMs, cps, newClickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withAimToleranceDegrees(double newAimToleranceDegrees) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, newAimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withFeedHunger(boolean newFeedHunger) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, newFeedHunger);
    }

    public @NotNull BoxerSettings withAim(@NotNull AimParams newAim) {
        return new BoxerSettings(pingMs, cps, clickJitter, newAim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withReach(double newReach) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, newReach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withWtap(@NotNull WTap newWtap) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                newWtap, movement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withMovement(@NotNull Movement newMovement) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, newMovement, invincible, feedHunger);
    }

    public @NotNull BoxerSettings withInvincible(boolean newInvincible) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, newInvincible, feedHunger);
    }
}

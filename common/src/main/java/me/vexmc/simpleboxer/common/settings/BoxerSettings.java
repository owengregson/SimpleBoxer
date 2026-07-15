package me.vexmc.simpleboxer.common.settings;

import me.vexmc.simpleboxer.common.aim.AimParams;
import org.jetbrains.annotations.NotNull;

/**
 * The complete behavior profile of one boxer — immutable, swapped by
 * reference (Mental's atomic-snapshot discipline). Defaults describe a
 * realistic sparring partner: no artificial ping, crisp-but-human aim,
 * spam-click cadence, straight rush, no w-tap, MORTAL (dies, drops its items,
 * and stays down until manually respawned). Every handicap, every survival
 * mode, and every skill technique is an explicit opt-in from here.
 *
 * <p>The schema is <b>append-only</b>: the ten original positional fields keep
 * their place and meaning; every capability added in the rework is a nested
 * sub-record tacked on the end, each with a DEFAULT/OFF constant that keeps the
 * two pinned round-trips ({@code parse(empty) == DEFAULTS},
 * {@code parse(write(s)) == s}) honest.</p>
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
        boolean feedHunger,
        @NotNull InvincibleMode invincibleMode,
        @NotNull Death death,
        @NotNull Combat combat,
        @NotNull SelfHeal selfHeal,
        @NotNull Items items,
        @NotNull Hunger hunger) {

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

    /**
     * How an invincible boxer survives a hit. Only consulted when
     * {@link #invincible()} is true.
     *
     * <ul>
     *   <li>{@code ZERO_DAMAGE} — the fixed, proper invincibility: the hit
     *       registers fully (knockback, i-frames, hurt animation) but the
     *       damage amount is zeroed at HIGHEST, so no burst can ever kill.</li>
     *   <li>{@code LEGACY_RESTORE} — the historical behavior: take the hit,
     *       restore health the next tick. Kept for tests that assert exact
     *       damage numbers through MONITOR; a one-shot beyond max HP still
     *       dies.</li>
     * </ul>
     */
    public enum InvincibleMode {
        ZERO_DAMAGE,
        LEGACY_RESTORE
    }

    /**
     * What happens when a boxer's health reaches zero.
     *
     * <ul>
     *   <li>{@code MANUAL} — the boxer dies, drops its items if
     *       {@link #dropItemsOnDeath()}, and stays down until it is manually
     *       respawned ({@code Boxer.respawn()} / the GUI). The default.</li>
     *   <li>{@code AUTO_RESPAWN} — the boxer respawns in place immediately (the
     *       historical behavior of an invincible fixture that was one-shot).</li>
     * </ul>
     */
    public record Death(boolean dropItemsOnDeath, @NotNull Mode mode) {

        public enum Mode {
            AUTO_RESPAWN,
            MANUAL
        }

        /** Realistic default: drop items, stay down until respawned. */
        public static final Death DEFAULT = new Death(true, Mode.MANUAL);
        /** Keep the kit, pop back up in place — the classic fixture behavior. */
        public static final Death RESPAWN_KEEP = new Death(false, Mode.AUTO_RESPAWN);
    }

    /**
     * Opt-in combat techniques. All off by default.
     *
     * @param blockHit       block between hits with a sword (needs Mental's
     *                       sword-blocking) to re-arm the sprint bonus.
     * @param rodKnockback   swap to a fishing rod to knock an approaching
     *                       target back before committing to a melee combo.
     * @param rodMin         nearest range (blocks) the boxer will rod-poke from.
     * @param rodMax         farthest range (blocks) the boxer will rod-poke from.
     * @param adaptiveStrafe choose strafe direction from how the opponent is
     *                       tracking the boxer (break a tight aim, exploit a mistrack).
     * @param sTap           straight-line s-tap combos (sprint reset, no A/D strafe).
     * @param missChance     fraction of clicks intentionally aimed off-target [0,1].
     */
    public record Combat(boolean blockHit, boolean rodKnockback, double rodMin, double rodMax,
            boolean adaptiveStrafe, boolean sTap, double missChance) {

        public static final Combat OFF = new Combat(false, false, 3.0, 6.0, false, false, 0.0);

        public Combat {
            if (rodMin < 0.5 || rodMin > 6.0) {
                throw new IllegalArgumentException("rodMin must be in [0.5,6]: " + rodMin);
            }
            if (rodMax < 0.5 || rodMax > 6.0) {
                throw new IllegalArgumentException("rodMax must be in [0.5,6]: " + rodMax);
            }
            if (rodMin > rodMax) {
                throw new IllegalArgumentException("rodMin must not exceed rodMax: " + rodMin + " > " + rodMax);
            }
            if (missChance < 0.0 || missChance > 1.0) {
                throw new IllegalArgumentException("missChance must be in [0,1]: " + missChance);
            }
        }
    }

    /**
     * Splash-potion self-heal. When {@code enabled}, a MORTAL boxer that drops
     * below {@code triggerHealth} disengages, retreats, throws instant-health
     * potions at its own feet until it recovers past {@code resumeHealth} (or
     * spends {@code splashCap} pots), then re-engages. Disjoint from
     * invincibility — an invincible boxer never needs to heal.
     */
    public record SelfHeal(boolean enabled, double triggerHealth, double resumeHealth, int splashCap) {

        public static final SelfHeal OFF = new SelfHeal(false, 6.0, 18.0, 6);

        public SelfHeal {
            if (triggerHealth < 0.0 || triggerHealth > 20.0) {
                throw new IllegalArgumentException("triggerHealth must be in [0,20]: " + triggerHealth);
            }
            if (resumeHealth < 0.0 || resumeHealth > 20.0) {
                throw new IllegalArgumentException("resumeHealth must be in [0,20]: " + resumeHealth);
            }
            if (triggerHealth > resumeHealth) {
                throw new IllegalArgumentException(
                        "triggerHealth must not exceed resumeHealth: " + triggerHealth + " > " + resumeHealth);
            }
            if (splashCap < 0 || splashCap > 36) {
                throw new IllegalArgumentException("splashCap must be in [0,36]: " + splashCap);
            }
        }
    }

    /**
     * Inventory behavior. {@code autoPickup} lets the boxer vacuum nearby item
     * entities into its real inventory; {@code lockLoadout} restores the classic
     * per-tick kit re-stamp (a pure fixture whose gear never changes). The slot
     * indices tell the brain which hotbar slot holds each tool.
     *
     * <p>{@code unbreakableKit} stamps every kit piece Unbreakable so the boxer's
     * gear never wears out. It DEFAULTS to {@code false} — a normal boxer's armor
     * chips on hit and its weapon dulls on attack, exactly like a real player. A
     * {@code lockLoadout} fixture is implicitly unbreakable regardless (its gear is
     * re-stamped every tick anyway), and calibration fixtures set it explicitly so
     * a boxer that spars forever never has its sword or armor break.</p>
     */
    public record Items(boolean autoPickup, boolean lockLoadout,
            int weaponSlot, int rodSlot, int potSlot, int foodSlot, int blockSlot,
            boolean unbreakableKit) {

        public static final Items DEFAULT = new Items(false, false, 0, 1, 2, 3, 4, false);

        public Items {
            requireHotbar("weaponSlot", weaponSlot);
            requireHotbar("rodSlot", rodSlot);
            requireHotbar("potSlot", potSlot);
            requireHotbar("foodSlot", foodSlot);
            requireHotbar("blockSlot", blockSlot);
        }

        private static void requireHotbar(String name, int slot) {
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException(name + " must be a hotbar slot in [0,8]: " + slot);
            }
        }
    }

    /**
     * Hunger behavior. By default {@link #feedHunger()} pins food full. Set
     * {@code natural} to let vanilla exhaustion run: the boxer then eats when
     * its food drops to {@code eatThreshold}.
     */
    public record Hunger(boolean natural, int eatThreshold) {

        public static final Hunger DEFAULT = new Hunger(false, 14);

        public Hunger {
            if (eatThreshold < 0 || eatThreshold > 20) {
                throw new IllegalArgumentException("eatThreshold must be in [0,20]: " + eatThreshold);
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
            false,
            true,
            InvincibleMode.ZERO_DAMAGE,
            Death.DEFAULT,
            Combat.OFF,
            SelfHeal.OFF,
            Items.DEFAULT,
            Hunger.DEFAULT);

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

    /* Wither-style copies for runtime tuning (/boxer set …). Every wither
     * threads the six rework sub-records through unchanged. */

    public @NotNull BoxerSettings withPingMs(int newPingMs) {
        return new BoxerSettings(newPingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withCps(double newCps) {
        return new BoxerSettings(pingMs, newCps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withClickJitter(double newClickJitter) {
        return new BoxerSettings(pingMs, cps, newClickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withAimToleranceDegrees(double newAimToleranceDegrees) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, newAimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withFeedHunger(boolean newFeedHunger) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, newFeedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withAim(@NotNull AimParams newAim) {
        return new BoxerSettings(pingMs, cps, clickJitter, newAim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withReach(double newReach) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, newReach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withWtap(@NotNull WTap newWtap) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                newWtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withMovement(@NotNull Movement newMovement) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, newMovement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withInvincible(boolean newInvincible) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, newInvincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withInvincibleMode(@NotNull InvincibleMode newMode) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                newMode, death, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withDeath(@NotNull Death newDeath) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, newDeath, combat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withCombat(@NotNull Combat newCombat) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, newCombat, selfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withSelfHeal(@NotNull SelfHeal newSelfHeal) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, newSelfHeal, items, hunger);
    }

    public @NotNull BoxerSettings withItems(@NotNull Items newItems) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, newItems, hunger);
    }

    public @NotNull BoxerSettings withHunger(@NotNull Hunger newHunger) {
        return new BoxerSettings(pingMs, cps, clickJitter, aim, reach, aimToleranceDegrees,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, newHunger);
    }
}

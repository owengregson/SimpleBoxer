package me.vexmc.simpleboxer.common.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Combat;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Death;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Hunger;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.InvincibleMode;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Items;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.SelfHeal;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.WTap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The built-in difficulty ladder. Each preset is a complete
 * {@link BoxerSettings} bundle — ping, CPS, aim, reach discipline, w-tap and
 * movement together describe "how good" a boxer is; every component stays
 * individually overridable at spawn or at runtime.
 *
 * <p>The classic tiers (dummy → aimbot) are stable calibration fixtures:
 * invincible (via the fixed zero-damage path), fed, and respawn-in-place, so a
 * sparring test never loses its opponent mid-run. {@code sweat} is the showcase
 * of the rework — a MORTAL, self-healing, technique-using partner (rod pokes,
 * blockhitting, adaptive strafing) that behaves like a real sweaty PvPer.</p>
 */
public final class DifficultyPresets {

    private DifficultyPresets() {}

    /** Stands still, never attacks — the punching bag. */
    public static final BoxerSettings DUMMY = new BoxerSettings(
            0, 0.0, 0.0, AimParams.SMOOTH, 3.0, 180.0,
            WTap.OFF, new Movement(Movement.Style.STAND, 0.0, false), true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP, Combat.OFF, SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /** High ping, slow sloppy clicks, drifting aim, walks rather than sprints. */
    public static final BoxerSettings EASY = new BoxerSettings(
            120, 4.0, 0.5, AimParams.SLOPPY, 2.8, 18.0,
            WTap.OFF, new Movement(Movement.Style.RUSH, 0.0, false), true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP, Combat.OFF, SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /** An ordinary player: average ping, mid CPS, smooth-pursuit aim. */
    public static final BoxerSettings MEDIUM = new BoxerSettings(
            60, 7.0, 0.35, AimParams.SMOOTH, 2.9, 12.0,
            WTap.OFF, Movement.RUSH, true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP, Combat.OFF, SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /** A practiced PvPer: low ping, 10 CPS, sharp aim, disciplined w-taps. */
    public static final BoxerSettings HARD = new BoxerSettings(
            35, 10.0, 0.25, AimParams.SHARP, 3.0, 8.0,
            new WTap(true, 1, 2), Movement.RUSH, true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP, Combat.OFF, SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /** Tournament-grade: tight aim spring, fast taps, circles its target. */
    public static final BoxerSettings EXPERT = new BoxerSettings(
            15, 13.0, 0.15, new AimParams(0.70, 0.25, 80.0), 3.0, 5.0,
            new WTap(true, 0, 2), new Movement(Movement.Style.STRAFE_CIRCLE, 0.0, true), true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP,
            new Combat(false, false, 3.0, 6.0, true, false, 0.0), SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /** The machine: zero ping, locked aim, saturated clicks — a calibrator. */
    public static final BoxerSettings AIMBOT = new BoxerSettings(
            0, 16.0, 0.05, AimParams.LOCKED, 3.0, 1.0,
            new WTap(true, 0, 2), Movement.RUSH, true, true,
            InvincibleMode.ZERO_DAMAGE, Death.RESPAWN_KEEP, Combat.OFF, SelfHeal.OFF,
            Items.DEFAULT, Hunger.DEFAULT);

    /**
     * The showcase: a MORTAL sweaty PvPer that uses the full technique set —
     * adaptive circle-strafing, rod pokes to knock an approaching target back,
     * blockhit combos (with Mental), and splash-pot self-heals when it drops
     * low — dying, dropping its kit, and needing food like a real player.
     */
    public static final BoxerSettings SWEAT = new BoxerSettings(
            20, 12.0, 0.18, AimParams.SHARP, 3.0, 5.0,
            new WTap(true, 0, 2), new Movement(Movement.Style.STRAFE_CIRCLE, 0.0, true),
            false, false,
            InvincibleMode.ZERO_DAMAGE, Death.DEFAULT,
            new Combat(true, true, 3.0, 5.5, true, false, 0.03),
            new SelfHeal(true, 8.0, 18.0, 6),
            new Items(true, false, 0, 1, 2, 3, 4),
            new Hunger(true, 14));

    private static final Map<String, BoxerSettings> BY_NAME = new LinkedHashMap<>();

    static {
        BY_NAME.put("dummy", DUMMY);
        BY_NAME.put("easy", EASY);
        BY_NAME.put("medium", MEDIUM);
        BY_NAME.put("hard", HARD);
        BY_NAME.put("expert", EXPERT);
        BY_NAME.put("aimbot", AIMBOT);
        BY_NAME.put("sweat", SWEAT);
    }

    public static @Nullable BoxerSettings byName(@NotNull String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    public static @NotNull Map<String, BoxerSettings> all() {
        return Map.copyOf(BY_NAME);
    }

    public static @NotNull Set<String> names() {
        return Collections.unmodifiableSet(BY_NAME.keySet());
    }
}

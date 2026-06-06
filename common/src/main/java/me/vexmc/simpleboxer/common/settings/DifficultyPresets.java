package me.vexmc.simpleboxer.common.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.WTap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The built-in difficulty ladder. Each preset is a complete
 * {@link BoxerSettings} bundle — ping, CPS, aim, reach discipline, w-tap and
 * movement together describe "how good" a boxer is; every component stays
 * individually overridable at spawn or at runtime.
 */
public final class DifficultyPresets {

    private DifficultyPresets() {}

    /** Stands still, never attacks — the punching bag. */
    public static final BoxerSettings DUMMY = new BoxerSettings(
            0, 0.0, 0.0, AimParams.SMOOTH, 3.0, 180.0,
            WTap.OFF, new Movement(Movement.Style.STAND, 2.5, false), true, true);

    /** High ping, slow sloppy clicks, drifting aim, walks rather than sprints. */
    public static final BoxerSettings EASY = new BoxerSettings(
            120, 4.0, 0.5, AimParams.SLOPPY, 2.8, 18.0,
            WTap.OFF, new Movement(Movement.Style.RUSH, 2.8, false), true, true);

    /** An ordinary player: average ping, mid CPS, smooth-pursuit aim. */
    public static final BoxerSettings MEDIUM = new BoxerSettings(
            60, 7.0, 0.35, AimParams.SMOOTH, 2.9, 12.0,
            WTap.OFF, Movement.RUSH, true, true);

    /** A practiced PvPer: low ping, 10 CPS, sharp aim, disciplined w-taps. */
    public static final BoxerSettings HARD = new BoxerSettings(
            35, 10.0, 0.25, AimParams.SHARP, 3.0, 8.0,
            new WTap(true, 1, 2), Movement.RUSH, true, true);

    /** Tournament-grade: tight aim spring, fast taps, circles its target. */
    public static final BoxerSettings EXPERT = new BoxerSettings(
            15, 13.0, 0.15, new AimParams(0.70, 0.25, 80.0), 3.0, 5.0,
            new WTap(true, 0, 2), new Movement(Movement.Style.STRAFE_CIRCLE, 2.6, true), true, true);

    /** The machine: zero ping, locked aim, saturated clicks — a calibrator. */
    public static final BoxerSettings AIMBOT = new BoxerSettings(
            0, 16.0, 0.05, AimParams.LOCKED, 3.0, 1.0,
            new WTap(true, 0, 2), Movement.RUSH, true, true);

    private static final Map<String, BoxerSettings> BY_NAME = new LinkedHashMap<>();

    static {
        BY_NAME.put("dummy", DUMMY);
        BY_NAME.put("easy", EASY);
        BY_NAME.put("medium", MEDIUM);
        BY_NAME.put("hard", HARD);
        BY_NAME.put("expert", EXPERT);
        BY_NAME.put("aimbot", AIMBOT);
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

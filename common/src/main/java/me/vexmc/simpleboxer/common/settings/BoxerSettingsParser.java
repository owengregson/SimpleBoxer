package me.vexmc.simpleboxer.common.settings;

import java.util.Locale;
import java.util.function.Consumer;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.WTap;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Layered warn-and-fallback parsing: every key missing from the section
 * inherits the base (so presets are sparse overlays over defaults, and
 * defaults over {@link BoxerSettings#DEFAULTS}); every malformed value warns
 * through the sink and keeps the base — a config typo can never torpedo a
 * spawn. {@code parse(empty, DEFAULTS) == DEFAULTS} is pinned by test.
 */
public final class BoxerSettingsParser {

    private BoxerSettingsParser() {}

    public static @NotNull BoxerSettings parse(
            @Nullable ConfigurationSection section,
            @NotNull BoxerSettings base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        int ping = intIn(section, "ping-ms", base.pingMs(), 0, 2000, warnings);
        double cps = doubleIn(section, "cps", base.cps(), 0.0, 50.0, warnings);
        double jitter = doubleIn(section, "click-jitter", base.clickJitter(), 0.0, 0.9, warnings);
        AimParams aim = parseAim(section.getConfigurationSection("aim"), base.aim(), warnings);
        double reach = doubleIn(section, "reach", base.reach(), 0.5, 6.0, warnings);
        double tolerance = doubleIn(section, "aim-tolerance-degrees",
                base.aimToleranceDegrees(), 0.0, 180.0, warnings);
        WTap wtap = parseWtap(section.getConfigurationSection("w-tap"), base.wtap(), warnings);
        Movement movement = parseMovement(section.getConfigurationSection("movement"),
                base.movement(), warnings);
        boolean invincible = section.getBoolean("invincible", base.invincible());
        boolean feedHunger = section.getBoolean("feed-hunger", base.feedHunger());
        return new BoxerSettings(ping, cps, jitter, aim, reach, tolerance,
                wtap, movement, invincible, feedHunger);
    }

    /**
     * Aim accepts a preset name ({@code preset: sharp}) and/or granular
     * overrides; granular keys override the chosen preset's components.
     */
    private static @NotNull AimParams parseAim(
            @Nullable ConfigurationSection section,
            @NotNull AimParams base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        AimParams chosen = base;
        String presetName = section.getString("preset");
        if (presetName != null) {
            AimParams preset = aimPresetByName(presetName);
            if (preset == null) {
                warnings.accept("aim.preset '" + presetName
                        + "' is not one of locked/sharp/smooth/sloppy — keeping the inherited aim");
            } else {
                chosen = preset;
            }
        }
        double stiffness = doubleIn(section, "stiffness", chosen.stiffness(), 0.0, 1.0, warnings);
        double damping = doubleIn(section, "damping", chosen.damping(), 0.0, 0.999, warnings);
        double maxVelocity = doubleIn(section, "max-velocity",
                chosen.maxVelocity(), 0.1, 360.0, warnings);
        return new AimParams(stiffness, damping, maxVelocity);
    }

    public static @Nullable AimParams aimPresetByName(@NotNull String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "locked" -> AimParams.LOCKED;
            case "sharp" -> AimParams.SHARP;
            case "smooth" -> AimParams.SMOOTH;
            case "sloppy" -> AimParams.SLOPPY;
            default -> null;
        };
    }

    private static @NotNull WTap parseWtap(
            @Nullable ConfigurationSection section,
            @NotNull WTap base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean enabled = section.getBoolean("enabled", base.enabled());
        int delay = intIn(section, "delay-ticks", base.delayTicks(), 0, 20, warnings);
        int release = intIn(section, "release-ticks", base.releaseTicks(), 1, 20, warnings);
        return new WTap(enabled, delay, release);
    }

    private static @NotNull Movement parseMovement(
            @Nullable ConfigurationSection section,
            @NotNull Movement base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        Movement.Style style = base.style();
        String styleName = section.getString("style");
        if (styleName != null) {
            try {
                style = Movement.Style.valueOf(styleName.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unknown) {
                warnings.accept("movement.style '" + styleName
                        + "' is not rush/strafe-circle/strafe-weave/stand — keeping "
                        + base.style().name().toLowerCase(Locale.ROOT));
            }
        }
        double stop = doubleIn(section, "stop-distance", base.stopDistance(), 0.0, 6.0, warnings);
        boolean sprint = section.getBoolean("sprint", base.sprint());
        return new Movement(style, stop, sprint);
    }

    private static int intIn(ConfigurationSection section, String key, int fallback,
            int min, int max, Consumer<String> warnings) {
        if (!section.contains(key)) {
            return fallback;
        }
        int value = section.getInt(key, fallback);
        if (value < min || value > max) {
            warnings.accept(key + " = " + value + " is outside [" + min + "," + max
                    + "] — using " + fallback);
            return fallback;
        }
        return value;
    }

    private static double doubleIn(ConfigurationSection section, String key, double fallback,
            double min, double max, Consumer<String> warnings) {
        if (!section.contains(key)) {
            return fallback;
        }
        double value = section.getDouble(key, fallback);
        if (Double.isNaN(value) || value < min || value > max) {
            warnings.accept(key + " = " + value + " is outside [" + min + "," + max
                    + "] — using " + fallback);
            return fallback;
        }
        return value;
    }
}

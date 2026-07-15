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
        BoxerSettings.InvincibleMode invincibleMode = parseInvincibleMode(
                section.getString("invincible-mode"), base.invincibleMode(), warnings);
        BoxerSettings.Death death = parseDeath(
                section.getConfigurationSection("death"), base.death(), warnings);
        BoxerSettings.Combat combat = parseCombat(
                section.getConfigurationSection("combat"), base.combat(), warnings);
        BoxerSettings.SelfHeal selfHeal = parseSelfHeal(
                section.getConfigurationSection("self-heal"), base.selfHeal(), warnings);
        BoxerSettings.Items items = parseItems(
                section.getConfigurationSection("items"), base.items(), warnings);
        BoxerSettings.Hunger hunger = parseHunger(
                section.getConfigurationSection("hunger"), base.hunger(), warnings);
        return new BoxerSettings(ping, cps, jitter, aim, reach, tolerance,
                wtap, movement, invincible, feedHunger,
                invincibleMode, death, combat, selfHeal, items, hunger);
    }

    private static @NotNull BoxerSettings.InvincibleMode parseInvincibleMode(
            @Nullable String name, @NotNull BoxerSettings.InvincibleMode base,
            @NotNull Consumer<String> warnings) {
        if (name == null) {
            return base;
        }
        try {
            return BoxerSettings.InvincibleMode.valueOf(
                    name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            warnings.accept("invincible-mode '" + name
                    + "' is not zero-damage/legacy-restore — keeping "
                    + base.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            return base;
        }
    }

    private static @NotNull BoxerSettings.Death parseDeath(
            @Nullable ConfigurationSection section, @NotNull BoxerSettings.Death base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean drop = section.getBoolean("drop-items", base.dropItemsOnDeath());
        BoxerSettings.Death.Mode mode = base.mode();
        String modeName = section.getString("mode");
        if (modeName != null) {
            try {
                mode = BoxerSettings.Death.Mode.valueOf(
                        modeName.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unknown) {
                warnings.accept("death.mode '" + modeName
                        + "' is not manual/auto-respawn — keeping "
                        + base.mode().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
        }
        return new BoxerSettings.Death(drop, mode);
    }

    private static @NotNull BoxerSettings.Combat parseCombat(
            @Nullable ConfigurationSection section, @NotNull BoxerSettings.Combat base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean blockHit = section.getBoolean("block-hit", base.blockHit());
        boolean rodKnockback = section.getBoolean("rod-knockback", base.rodKnockback());
        double rodMin = doubleIn(section, "rod-min", base.rodMin(), 0.5, 6.0, warnings);
        double rodMax = doubleIn(section, "rod-max", base.rodMax(), 0.5, 6.0, warnings);
        if (rodMin > rodMax) {
            warnings.accept("combat.rod-min (" + rodMin + ") exceeds rod-max (" + rodMax
                    + ") — keeping the inherited rod band");
            rodMin = base.rodMin();
            rodMax = base.rodMax();
        }
        boolean adaptiveStrafe = section.getBoolean("adaptive-strafe", base.adaptiveStrafe());
        boolean sTap = section.getBoolean("s-tap", base.sTap());
        double missChance = doubleIn(section, "miss-chance", base.missChance(), 0.0, 1.0, warnings);
        return new BoxerSettings.Combat(blockHit, rodKnockback, rodMin, rodMax,
                adaptiveStrafe, sTap, missChance);
    }

    private static @NotNull BoxerSettings.SelfHeal parseSelfHeal(
            @Nullable ConfigurationSection section, @NotNull BoxerSettings.SelfHeal base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean enabled = section.getBoolean("enabled", base.enabled());
        double trigger = doubleIn(section, "trigger-health", base.triggerHealth(), 0.0, 20.0, warnings);
        double resume = doubleIn(section, "resume-health", base.resumeHealth(), 0.0, 20.0, warnings);
        if (trigger > resume) {
            warnings.accept("self-heal.trigger-health (" + trigger + ") exceeds resume-health ("
                    + resume + ") — keeping the inherited thresholds");
            trigger = base.triggerHealth();
            resume = base.resumeHealth();
        }
        int cap = intIn(section, "splash-cap", base.splashCap(), 0, 36, warnings);
        return new BoxerSettings.SelfHeal(enabled, trigger, resume, cap);
    }

    private static @NotNull BoxerSettings.Items parseItems(
            @Nullable ConfigurationSection section, @NotNull BoxerSettings.Items base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean autoPickup = section.getBoolean("auto-pickup", base.autoPickup());
        boolean lockLoadout = section.getBoolean("lock-loadout", base.lockLoadout());
        int weapon = intIn(section, "weapon-slot", base.weaponSlot(), 0, 8, warnings);
        int rod = intIn(section, "rod-slot", base.rodSlot(), 0, 8, warnings);
        int pot = intIn(section, "pot-slot", base.potSlot(), 0, 8, warnings);
        int food = intIn(section, "food-slot", base.foodSlot(), 0, 8, warnings);
        int block = intIn(section, "block-slot", base.blockSlot(), 0, 8, warnings);
        boolean unbreakableKit = section.getBoolean("unbreakable-kit", base.unbreakableKit());
        boolean fillSplashPots = section.getBoolean("fill-splash-pots", base.fillSplashPots());
        int splashPotCount = intIn(section, "splash-pot-count", base.splashPotCount(), 0, 9, warnings);
        return new BoxerSettings.Items(autoPickup, lockLoadout, weapon, rod, pot, food, block,
                unbreakableKit, fillSplashPots, splashPotCount);
    }

    private static @NotNull BoxerSettings.Hunger parseHunger(
            @Nullable ConfigurationSection section, @NotNull BoxerSettings.Hunger base,
            @NotNull Consumer<String> warnings) {
        if (section == null) {
            return base;
        }
        boolean natural = section.getBoolean("natural", base.natural());
        int threshold = intIn(section, "eat-threshold", base.eatThreshold(), 0, 20, warnings);
        return new BoxerSettings.Hunger(natural, threshold);
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

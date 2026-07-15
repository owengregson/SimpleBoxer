package me.vexmc.simpleboxer.common.settings;

import java.util.Locale;
import me.vexmc.simpleboxer.common.aim.AimParams;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The exact inverse of {@link BoxerSettingsParser}: serialises a complete
 * {@link BoxerSettings} into a configuration section the parser reads back
 * unchanged. The GUI uses it to persist GUI-edited {@code defaults} and named
 * presets to {@code config.yml}. The round-trip
 * {@code parse(write(s)) == s} is pinned by test.
 *
 * <p>Aim is written as a {@code preset:} name when it matches a built-in spring
 * (the common case, and the human-readable one), and as the three granular
 * knobs otherwise — either way the parser reconstructs the same
 * {@link AimParams}. The target section is fully rewritten, so a key the new
 * value no longer needs (a stale granular knob under a now-preset aim) never
 * lingers.</p>
 */
public final class BoxerSettingsWriter {

    private BoxerSettingsWriter() {}

    public static void write(@NotNull ConfigurationSection section, @NotNull BoxerSettings settings) {
        section.set("ping-ms", settings.pingMs());
        section.set("cps", settings.cps());
        section.set("click-jitter", settings.clickJitter());

        ConfigurationSection aim = fresh(section, "aim");
        String preset = aimPresetName(settings.aim());
        if (preset != null) {
            aim.set("preset", preset);
        } else {
            aim.set("stiffness", settings.aim().stiffness());
            aim.set("damping", settings.aim().damping());
            aim.set("max-velocity", settings.aim().maxVelocity());
        }

        section.set("reach", settings.reach());
        section.set("aim-tolerance-degrees", settings.aimToleranceDegrees());

        ConfigurationSection wtap = fresh(section, "w-tap");
        wtap.set("enabled", settings.wtap().enabled());
        wtap.set("delay-ticks", settings.wtap().delayTicks());
        wtap.set("release-ticks", settings.wtap().releaseTicks());

        ConfigurationSection movement = fresh(section, "movement");
        movement.set("style", styleName(settings.movement().style()));
        movement.set("stop-distance", settings.movement().stopDistance());
        movement.set("sprint", settings.movement().sprint());

        section.set("invincible", settings.invincible());
        section.set("feed-hunger", settings.feedHunger());
        section.set("invincible-mode", token(settings.invincibleMode().name()));

        ConfigurationSection death = fresh(section, "death");
        death.set("drop-items", settings.death().dropItemsOnDeath());
        death.set("mode", token(settings.death().mode().name()));

        ConfigurationSection combat = fresh(section, "combat");
        combat.set("block-hit", settings.combat().blockHit());
        combat.set("rod-knockback", settings.combat().rodKnockback());
        combat.set("rod-min", settings.combat().rodMin());
        combat.set("rod-max", settings.combat().rodMax());
        combat.set("adaptive-strafe", settings.combat().adaptiveStrafe());
        combat.set("s-tap", settings.combat().sTap());
        combat.set("miss-chance", settings.combat().missChance());

        ConfigurationSection selfHeal = fresh(section, "self-heal");
        selfHeal.set("enabled", settings.selfHeal().enabled());
        selfHeal.set("trigger-health", settings.selfHeal().triggerHealth());
        selfHeal.set("resume-health", settings.selfHeal().resumeHealth());
        selfHeal.set("splash-cap", settings.selfHeal().splashCap());

        ConfigurationSection items = fresh(section, "items");
        items.set("auto-pickup", settings.items().autoPickup());
        items.set("lock-loadout", settings.items().lockLoadout());
        items.set("weapon-slot", settings.items().weaponSlot());
        items.set("rod-slot", settings.items().rodSlot());
        items.set("pot-slot", settings.items().potSlot());
        items.set("food-slot", settings.items().foodSlot());
        items.set("block-slot", settings.items().blockSlot());
        items.set("unbreakable-kit", settings.items().unbreakableKit());
        items.set("fill-splash-pots", settings.items().fillSplashPots());
        items.set("splash-pot-count", settings.items().splashPotCount());

        ConfigurationSection hunger = fresh(section, "hunger");
        hunger.set("natural", settings.hunger().natural());
        hunger.set("eat-threshold", settings.hunger().eatThreshold());
    }

    /** An enum constant as its config token: {@code ZERO_DAMAGE -> zero-damage}. */
    public static @NotNull String token(@NotNull String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The config token for a movement style: {@code STRAFE_CIRCLE -> strafe-circle}. */
    public static @NotNull String styleName(@NotNull BoxerSettings.Movement.Style style) {
        return style.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The built-in aim preset this spring equals, or {@code null} if custom. */
    public static @Nullable String aimPresetName(@NotNull AimParams aim) {
        if (aim.equals(AimParams.LOCKED)) {
            return "locked";
        }
        if (aim.equals(AimParams.SHARP)) {
            return "sharp";
        }
        if (aim.equals(AimParams.SMOOTH)) {
            return "smooth";
        }
        if (aim.equals(AimParams.SLOPPY)) {
            return "sloppy";
        }
        return null;
    }

    /** A cleared child section — drops any keys a previous write left behind. */
    private static @NotNull ConfigurationSection fresh(
            @NotNull ConfigurationSection parent, @NotNull String name) {
        parent.set(name, null);
        return parent.createSection(name);
    }
}

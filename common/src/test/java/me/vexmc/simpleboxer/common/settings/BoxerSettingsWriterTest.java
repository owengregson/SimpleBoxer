package me.vexmc.simpleboxer.common.settings;

import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.WTap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writer is the exact inverse of the parser: anything the GUI persists must
 * read back identical, or a saved preset would silently drift on reload.
 */
class BoxerSettingsWriterTest {

    private static BoxerSettings roundTrip(BoxerSettings settings) {
        YamlConfiguration section = new YamlConfiguration();
        BoxerSettingsWriter.write(section, settings);
        return BoxerSettingsParser.parse(section, BoxerSettings.DEFAULTS, warning -> {
            throw new AssertionError("writer emitted a value the parser rejects: " + warning);
        });
    }

    @Test
    void defaultsRoundTrip() {
        assertEquals(BoxerSettings.DEFAULTS, roundTrip(BoxerSettings.DEFAULTS));
    }

    @Test
    void everyBuiltinPresetRoundTrips() {
        for (BoxerSettings preset : DifficultyPresets.all().values()) {
            assertEquals(preset, roundTrip(preset),
                    "built-in preset must survive a write/parse cycle unchanged");
        }
    }

    @Test
    void customAimAndStyleRoundTrip() {
        BoxerSettings custom = new BoxerSettings(
                175, 13.5, 0.42, new AimParams(0.61, 0.33, 47.0),
                4.25, 3.5, new WTap(true, 4, 7),
                new Movement(Movement.Style.STRAFE_WEAVE, 2.5, false), false, false,
                BoxerSettings.InvincibleMode.ZERO_DAMAGE, BoxerSettings.Death.DEFAULT,
                BoxerSettings.Combat.OFF, BoxerSettings.SelfHeal.OFF,
                BoxerSettings.Items.DEFAULT, BoxerSettings.Hunger.DEFAULT,
                BoxerSettings.CritSpam.OFF);
        assertEquals(custom, roundTrip(custom));
    }

    @Test
    void reworkSubRecordsRoundTrip() {
        BoxerSettings custom = new BoxerSettings(
                20, 12.0, 0.18, AimParams.SHARP, 3.0, 5.0, new WTap(true, 0, 2),
                new Movement(Movement.Style.STRAFE_CIRCLE, 1.5, true), false, false,
                BoxerSettings.InvincibleMode.LEGACY_RESTORE,
                new BoxerSettings.Death(true, BoxerSettings.Death.Mode.MANUAL),
                new BoxerSettings.Combat(true, true, 2.5, 5.5,
                        BoxerSettings.Combat.StrafePreset.JUKE, true, 0.07),
                new BoxerSettings.SelfHeal(true, 7.0, 17.0, 4),
                new BoxerSettings.Items(true, true, 1, 2, 3, 4, 5, true, true, 5),
                new BoxerSettings.Hunger(true, 12),
                BoxerSettings.CritSpam.ON);
        assertEquals(custom, roundTrip(custom));
    }

    @Test
    void itemsUnbreakableKitDefaultsToWearAndRoundTrips() {
        // The default kit wears (unbreakable-kit = false); toggling it on must
        // survive a write/parse cycle so a GUI-forced unbreakable kit persists.
        assertFalse(BoxerSettings.Items.DEFAULT.unbreakableKit(), "default kit wears");
        assertFalse(BoxerSettings.DEFAULTS.items().unbreakableKit());
        assertEquals(BoxerSettings.DEFAULTS, roundTrip(BoxerSettings.DEFAULTS));

        BoxerSettings unbreakable = BoxerSettings.DEFAULTS.withItems(
                new BoxerSettings.Items(false, false, 0, 1, 2, 3, 4, true, false, 0));
        assertTrue(unbreakable.items().unbreakableKit());
        assertEquals(unbreakable, roundTrip(unbreakable),
                "an unbreakable-kit Items must survive write/parse unchanged");
    }

    @Test
    void wholeInventorySplashPotCountRoundTrips() {
        // The widened [0,36] ceiling: a maxed supply must survive write/parse —
        // the parser and the record validation share the new bound.
        BoxerSettings maxed = BoxerSettings.DEFAULTS.withItems(
                new BoxerSettings.Items(false, false, 0, 1, 2, 3, 4, false, true, 36));
        assertEquals(36, maxed.items().splashPotCount());
        assertEquals(maxed, roundTrip(maxed), "a 36-pot supply survives write/parse");
    }

    @Test
    void splashPotSupplyRoundTrips() {
        // No finite splash-pot supply by default; toggling it on with a count must
        // survive a write/parse cycle so a configured supply persists.
        assertFalse(BoxerSettings.Items.DEFAULT.fillSplashPots(), "no splash pots by default");
        assertEquals(0, BoxerSettings.Items.DEFAULT.splashPotCount());
        BoxerSettings withPots = BoxerSettings.DEFAULTS.withItems(
                new BoxerSettings.Items(false, false, 0, 1, 2, 3, 4, false, true, 6));
        assertTrue(withPots.items().fillSplashPots());
        assertEquals(6, withPots.items().splashPotCount());
        assertEquals(withPots, roundTrip(withPots), "splash-pot supply survives write/parse");
    }

    @Test
    void namedAimWritesAPresetKeyAndCustomAimWritesGranular() {
        YamlConfiguration named = new YamlConfiguration();
        BoxerSettingsWriter.write(named, BoxerSettings.DEFAULTS); // aim = SHARP
        assertEquals("sharp", named.getString("aim.preset"));
        assertNull(named.getString("aim.stiffness"), "a named aim writes no granular keys");

        YamlConfiguration custom = new YamlConfiguration();
        BoxerSettingsWriter.write(custom,
                BoxerSettings.DEFAULTS.withAim(new AimParams(0.61, 0.33, 47.0)));
        assertNull(custom.getString("aim.preset"), "a custom aim writes no preset name");
        assertTrue(custom.isDouble("aim.stiffness") || custom.contains("aim.stiffness"),
                "a custom aim writes granular knobs");
    }

    @Test
    void aimPresetNameReverseLookup() {
        assertEquals("locked", BoxerSettingsWriter.aimPresetName(AimParams.LOCKED));
        assertEquals("sharp", BoxerSettingsWriter.aimPresetName(AimParams.SHARP));
        assertEquals("smooth", BoxerSettingsWriter.aimPresetName(AimParams.SMOOTH));
        assertEquals("sloppy", BoxerSettingsWriter.aimPresetName(AimParams.SLOPPY));
        assertNull(BoxerSettingsWriter.aimPresetName(new AimParams(0.5, 0.5, 50.0)));
    }

    @Test
    void styleTokenMatchesTheParser() {
        assertEquals("strafe-circle",
                BoxerSettingsWriter.styleName(Movement.Style.STRAFE_CIRCLE));
        assertEquals("rush", BoxerSettingsWriter.styleName(Movement.Style.RUSH));
        assertEquals("stand", BoxerSettingsWriter.styleName(Movement.Style.STAND));
    }

    @Test
    void critSpamRoundTrips() {
        BoxerSettings on = BoxerSettings.DEFAULTS.withCritSpam(BoxerSettings.CritSpam.ON);
        assertTrue(on.critSpam().enabled());
        assertEquals(on, roundTrip(on), "combat.crit-spam survives write/parse");
    }
}

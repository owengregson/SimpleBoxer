package me.vexmc.simpleboxer.common.settings;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxerSettingsParserTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return configuration;
    }

    @Test
    void emptySectionEqualsTheBase() {
        List<String> warnings = new ArrayList<>();
        BoxerSettings parsed = BoxerSettingsParser.parse(
                yaml(""), BoxerSettings.DEFAULTS, warnings::add);
        assertEquals(BoxerSettings.DEFAULTS, parsed, "parse(empty) == DEFAULTS");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void fullSectionRoundTrips() {
        List<String> warnings = new ArrayList<>();
        BoxerSettings parsed = BoxerSettingsParser.parse(yaml("""
                ping-ms: 80
                cps: 11.5
                click-jitter: 0.2
                aim:
                  preset: sloppy
                reach: 2.7
                aim-tolerance-degrees: 6.5
                w-tap:
                  enabled: true
                  delay-ticks: 2
                  release-ticks: 3
                movement:
                  style: strafe-circle
                  stop-distance: 2.2
                  sprint: false
                invincible: false
                feed-hunger: false
                """), BoxerSettings.DEFAULTS, warnings::add);
        assertEquals(80, parsed.pingMs());
        assertEquals(11.5, parsed.cps());
        assertEquals(0.2, parsed.clickJitter());
        assertEquals(AimParams.SLOPPY, parsed.aim());
        assertEquals(2.7, parsed.reach());
        assertEquals(6.5, parsed.aimToleranceDegrees());
        assertTrue(parsed.wtap().enabled());
        assertEquals(2, parsed.wtap().delayTicks());
        assertEquals(3, parsed.wtap().releaseTicks());
        assertEquals(Movement.Style.STRAFE_CIRCLE, parsed.movement().style());
        assertEquals(2.2, parsed.movement().stopDistance());
        assertFalse(parsed.movement().sprint());
        assertFalse(parsed.invincible());
        assertFalse(parsed.feedHunger());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void granularAimOverridesThePreset() {
        BoxerSettings parsed = BoxerSettingsParser.parse(yaml("""
                aim:
                  preset: sharp
                  max-velocity: 25.0
                """), BoxerSettings.DEFAULTS, ignored -> {});
        assertEquals(AimParams.SHARP.stiffness(), parsed.aim().stiffness());
        assertEquals(AimParams.SHARP.damping(), parsed.aim().damping());
        assertEquals(25.0, parsed.aim().maxVelocity(), "granular key wins over the preset");
    }

    @Test
    void outOfRangeWarnsAndKeepsTheBase() {
        List<String> warnings = new ArrayList<>();
        BoxerSettings parsed = BoxerSettingsParser.parse(yaml("""
                ping-ms: 99999
                cps: -3
                """), BoxerSettings.DEFAULTS, warnings::add);
        assertEquals(BoxerSettings.DEFAULTS.pingMs(), parsed.pingMs());
        assertEquals(BoxerSettings.DEFAULTS.cps(), parsed.cps());
        assertEquals(2, warnings.size(), "one warning per bad key");
    }

    @Test
    void unknownNamesWarnAndKeepInherited() {
        List<String> warnings = new ArrayList<>();
        BoxerSettings parsed = BoxerSettingsParser.parse(yaml("""
                aim:
                  preset: telepathic
                movement:
                  style: moonwalk
                """), BoxerSettings.DEFAULTS, warnings::add);
        assertEquals(BoxerSettings.DEFAULTS.aim(), parsed.aim());
        assertEquals(BoxerSettings.DEFAULTS.movement().style(), parsed.movement().style());
        assertEquals(2, warnings.size());
    }

    @Test
    void itemsUnbreakableKitParses() {
        BoxerSettings parsed = BoxerSettingsParser.parse(yaml("""
                items:
                  unbreakable-kit: true
                """), BoxerSettings.DEFAULTS, ignored -> {});
        assertTrue(parsed.items().unbreakableKit(), "items.unbreakable-kit reads through");
        assertFalse(BoxerSettings.DEFAULTS.items().unbreakableKit(), "the default kit wears");
    }

    @Test
    void sparseOverlayInheritsEverythingElse() {
        BoxerSettings parsed = BoxerSettingsParser.parse(
                yaml("ping-ms: 150"), DifficultyPresets.HARD, ignored -> {});
        assertEquals(150, parsed.pingMs(), "the overlay key");
        assertEquals(DifficultyPresets.HARD.cps(), parsed.cps(), "everything else from the preset");
        assertEquals(DifficultyPresets.HARD.wtap(), parsed.wtap());
    }

    @Test
    void presetCatalogCarriesItsCanonicalValues() {
        // The ladder's shape is part of the product: pin each tier's identity
        // so a refactor can never silently re-balance "hard".
        assertEquals(0.0, DifficultyPresets.DUMMY.cps());
        assertEquals(Movement.Style.STAND, DifficultyPresets.DUMMY.movement().style());
        assertEquals(120, DifficultyPresets.EASY.pingMs());
        assertFalse(DifficultyPresets.EASY.movement().sprint());
        assertEquals(60, DifficultyPresets.MEDIUM.pingMs());
        assertEquals(7.0, DifficultyPresets.MEDIUM.cps());
        assertEquals(35, DifficultyPresets.HARD.pingMs());
        assertEquals(10.0, DifficultyPresets.HARD.cps());
        assertTrue(DifficultyPresets.HARD.wtap().enabled());
        assertEquals(AimParams.SHARP, DifficultyPresets.HARD.aim());
        assertEquals(Movement.Style.STRAFE_CIRCLE, DifficultyPresets.EXPERT.movement().style());
        assertEquals(new AimParams(0.70, 0.25, 80.0), DifficultyPresets.EXPERT.aim());
        assertEquals(AimParams.LOCKED, DifficultyPresets.AIMBOT.aim());
        assertEquals(0, DifficultyPresets.AIMBOT.pingMs());
        assertNotNull(DifficultyPresets.byName("HaRd"), "lookup is case-insensitive");
        assertEquals(7, DifficultyPresets.names().size());
        // The classic ladder are invincible, fed calibration fixtures — a
        // sparring test never loses its opponent.
        for (String tier : new String[] {"dummy", "easy", "medium", "hard", "expert", "aimbot"}) {
            BoxerSettings preset = DifficultyPresets.byName(tier);
            assertNotNull(preset);
            assertTrue(preset.invincible(), tier + " is an invincible fixture");
            assertTrue(preset.feedHunger(), tier + " is fed");
        }
        // sweat is the mortal, self-healing, technique-using showcase.
        assertFalse(DifficultyPresets.SWEAT.invincible());
        assertEquals(BoxerSettings.Death.Mode.MANUAL, DifficultyPresets.SWEAT.death().mode());
        assertTrue(DifficultyPresets.SWEAT.death().dropItemsOnDeath());
        assertTrue(DifficultyPresets.SWEAT.combat().blockHit());
        assertTrue(DifficultyPresets.SWEAT.combat().rodKnockback());
        assertEquals(BoxerSettings.Combat.StrafePreset.WTAP_SYNC,
                DifficultyPresets.SWEAT.combat().strafePreset());
        assertTrue(DifficultyPresets.SWEAT.selfHeal().enabled());
        assertTrue(DifficultyPresets.SWEAT.hunger().natural());
    }
}

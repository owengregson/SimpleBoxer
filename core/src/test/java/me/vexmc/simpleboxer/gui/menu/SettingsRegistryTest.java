package me.vexmc.simpleboxer.gui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Hunger;
import org.junit.jupiter.api.Test;

/**
 * Pins the recut registry: the category census, the three-state hunger knob's
 * totality over its two underlying booleans, and the dependency wiring the
 * renderer dims by. Hunger semantics come from the consumers —
 * {@code HungerGuard} pins food only while {@code feedHunger && !natural}
 * (HungerGuard.java:35) and {@code SeekFoodGoal} eats only while
 * {@code natural} (SeekFoodGoal.java:82) — so {@code (true, true)} already
 * behaves as natural and must read as it.
 */
class SettingsRegistryTest {

    @Test
    void categoryCensusMatchesTheRecut() {
        // 6 aim + 9 combat + 4 movement + 6 potions + 6 survival + 3 items = 34
        // (the old 40, minus five hotbar-slot integers moved to the layout
        // screen, minus feed-hunger and hunger-natural, plus the hunger cycle).
        assertEquals(6, SettingsRegistry.byCategory(SettingCategory.AIM).size());
        assertEquals(9, SettingsRegistry.byCategory(SettingCategory.COMBAT).size());
        assertEquals(4, SettingsRegistry.byCategory(SettingCategory.MOVEMENT).size());
        assertEquals(6, SettingsRegistry.byCategory(SettingCategory.POTIONS).size());
        assertEquals(6, SettingsRegistry.byCategory(SettingCategory.SURVIVAL).size());
        assertEquals(3, SettingsRegistry.byCategory(SettingCategory.ITEMS).size());
    }

    @Test
    void theHubCanLayOutEveryCategory() {
        // tileSlots fails loudly past nine tiles; the enum must stay inside.
        assertEquals(SettingCategory.values().length,
                SettingsHubMenu.tileSlots(SettingCategory.values().length).length);
    }

    @Test
    void theItemsPageShedItsSlotKnobs() {
        List<SettingDescriptor> items = SettingsRegistry.byCategory(SettingCategory.ITEMS);
        assertEquals("Auto-pickup", items.get(0).name());
        assertEquals("Lock loadout", items.get(1).name());
        assertEquals("Unbreakable kit", items.get(2).name());
    }

    @Test
    void hungerCycleMatchesExactlyOneOptionInEveryBooleanState() {
        SettingDescriptor hunger = byName(SettingCategory.SURVIVAL, "Hunger");
        assertEquals("pinned-full", onlyMatch(hunger, state(true, false)));
        assertEquals("natural", onlyMatch(hunger, state(true, true)));
        assertEquals("natural", onlyMatch(hunger, state(false, true)));
        assertEquals("untouched", onlyMatch(hunger, state(false, false)));
    }

    @Test
    void hungerSelectionKeepsTheThresholdAndNormalisesTheRedundantState() {
        SettingDescriptor hunger = byName(SettingCategory.SURVIVAL, "Hunger");
        // Selecting natural from the redundant (feedHunger=true, natural=true)
        // normalises feedHunger to false; the threshold (7) rides untouched.
        BoxerSettings natural = apply(hunger, "natural", state(true, true));
        assertFalse(natural.feedHunger());
        assertTrue(natural.hunger().natural());
        assertEquals(7, natural.hunger().eatThreshold());

        BoxerSettings pinned = apply(hunger, "pinned-full", natural);
        assertTrue(pinned.feedHunger());
        assertFalse(pinned.hunger().natural());
        assertEquals(7, pinned.hunger().eatThreshold());

        BoxerSettings untouched = apply(hunger, "untouched", pinned);
        assertFalse(untouched.feedHunger());
        assertFalse(untouched.hunger().natural());
        assertEquals(7, untouched.hunger().eatThreshold());
    }

    @Test
    void dependentKnobsDimExactlyWhileTheirMasterIsOff() {
        // DEFAULTS ships every master off: WTap.OFF, Combat.OFF (rod off),
        // SelfHeal.OFF, fillSplashPots=false, Hunger.DEFAULT (natural=false).
        BoxerSettings off = BoxerSettings.DEFAULTS;
        BoxerSettings rodOn = flip(SettingCategory.COMBAT, "Rod knockback", off);
        BoxerSettings wtapOn = flip(SettingCategory.COMBAT, "W-tap", off);
        BoxerSettings healOn = flip(SettingCategory.POTIONS, "Self-heal", off);
        BoxerSettings potsOn = flip(SettingCategory.POTIONS, "Fill splash pots", off);
        BoxerSettings naturalOn = off.withHunger(new Hunger(true, 14));

        assertDimming(SettingCategory.COMBAT, "Rod min range", "Rod knockback", off, rodOn);
        assertDimming(SettingCategory.COMBAT, "Rod max range", "Rod knockback", off, rodOn);
        assertDimming(SettingCategory.COMBAT, "W-tap delay", "W-tap", off, wtapOn);
        assertDimming(SettingCategory.COMBAT, "W-tap release", "W-tap", off, wtapOn);
        assertDimming(SettingCategory.POTIONS, "Heal trigger HP", "Self-heal", off, healOn);
        assertDimming(SettingCategory.POTIONS, "Heal resume HP", "Self-heal", off, healOn);
        assertDimming(SettingCategory.POTIONS, "Splash cap", "Self-heal", off, healOn);
        assertDimming(SettingCategory.POTIONS, "Splash pot count", "Fill splash pots",
                off, potsOn);
        assertDimming(SettingCategory.SURVIVAL, "Eat threshold", "Hunger: natural",
                off, naturalOn);
    }

    @Test
    void independentKnobsNeverDim() {
        SettingDescriptor ping = byName(SettingCategory.AIM, "Ping");
        assertNull(ping.requiresLabel());
        assertTrue(ping.enabledFor(BoxerSettings.DEFAULTS));
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static SettingDescriptor byName(SettingCategory category, String name) {
        for (SettingDescriptor d : SettingsRegistry.byCategory(category)) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        throw new AssertionError("no descriptor named '" + name + "' in " + category);
    }

    /** DEFAULTS with the two hunger booleans forced and a marker threshold of 7. */
    private static BoxerSettings state(boolean feedHunger, boolean natural) {
        return BoxerSettings.DEFAULTS.withFeedHunger(feedHunger)
                .withHunger(new Hunger(natural, 7));
    }

    /** The single matching option's label — fails on zero or two matches. */
    private static String onlyMatch(SettingDescriptor cycle, BoxerSettings s) {
        String label = null;
        for (SettingDescriptor.CycleOption option : cycle.options()) {
            if (option.matches().test(s)) {
                assertNull(label, "both '" + label + "' and '" + option.label() + "' match");
                label = option.label();
            }
        }
        assertNotNull(label, "no option matches");
        return label;
    }

    /** Select the named cycle option on {@code s}. */
    private static BoxerSettings apply(SettingDescriptor cycle, String label, BoxerSettings s) {
        for (SettingDescriptor.CycleOption option : cycle.options()) {
            if (option.label().equals(label)) {
                return option.select().apply(s);
            }
        }
        throw new AssertionError("no option labelled '" + label + "'");
    }

    /** Flip the named TOGGLE descriptor — turning a master on the way the GUI does. */
    private static BoxerSettings flip(SettingCategory category, String name, BoxerSettings s) {
        return byName(category, name).toggle().apply(s);
    }

    private static void assertDimming(SettingCategory category, String knob, String master,
            BoxerSettings masterOff, BoxerSettings masterOn) {
        SettingDescriptor d = byName(category, knob);
        assertEquals(master, d.requiresLabel(), knob + " cites the wrong master");
        assertFalse(d.enabledFor(masterOff), knob + " should dim while " + master + " is off");
        assertTrue(d.enabledFor(masterOn), knob + " should light up with " + master + " on");
    }
}

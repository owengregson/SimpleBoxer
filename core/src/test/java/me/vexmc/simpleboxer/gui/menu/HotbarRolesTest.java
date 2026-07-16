package me.vexmc.simpleboxer.gui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Items;
import me.vexmc.simpleboxer.gui.menu.HotbarRoles.Role;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link HotbarRoles}' cycle semantics against the default layout
 * {@code Items.DEFAULT} = weapon 0 · rod 1 · potion 2 · food 3 · block 4
 * (slots 5-8 free). The invariant under test — all five role slots distinct
 * after every click — is what keeps {@code BoxerImpl.seedConsumables}'s
 * {@code Set.of(weapon, rod, food, block)} from throwing.
 */
class HotbarRolesTest {

    @Test
    void roleAtReadsTheDefaultLayout() {
        assertEquals(Role.WEAPON, HotbarRoles.roleAt(Items.DEFAULT, 0));
        assertEquals(Role.ROD, HotbarRoles.roleAt(Items.DEFAULT, 1));
        assertEquals(Role.POTION, HotbarRoles.roleAt(Items.DEFAULT, 2));
        assertEquals(Role.FOOD, HotbarRoles.roleAt(Items.DEFAULT, 3));
        assertEquals(Role.BLOCK, HotbarRoles.roleAt(Items.DEFAULT, 4));
        for (int slot = 5; slot <= 8; slot++) {
            assertNull(HotbarRoles.roleAt(Items.DEFAULT, slot));
        }
    }

    @Test
    void clickingARoleSwapsWithTheNextRolesHolder() {
        // Slot 0 holds WEAPON; next in cycle is ROD, at slot 1 → they swap:
        // weapon 1, rod 0, everything else untouched.
        Items out = HotbarRoles.cycle(Items.DEFAULT, 0);
        assertEquals(1, out.weaponSlot());
        assertEquals(0, out.rodSlot());
        assertEquals(2, out.potSlot());
        assertEquals(3, out.foodSlot());
        assertEquals(4, out.blockSlot());
        assertAllDistinct(out);
    }

    @Test
    void clickingAnEmptySlotPullsWeaponOntoIt() {
        // Slot 5 is free → it acquires WEAPON; weapon's old slot 0 goes free.
        Items out = HotbarRoles.cycle(Items.DEFAULT, 5);
        assertEquals(5, out.weaponSlot());
        assertNull(HotbarRoles.roleAt(out, 0));
        assertAllDistinct(out);
    }

    @Test
    void clickingBlockFreesTheSlotAndParksBlockOnTheLowestFreeSlot() {
        // Slot 4 holds BLOCK → next is empty. Block must still live somewhere:
        // slots 0-3 are taken (weapon/rod/potion/food), 4 is the clicked slot,
        // so the lowest free slot is 5.
        Items out = HotbarRoles.cycle(Items.DEFAULT, 4);
        assertEquals(5, out.blockSlot());
        assertNull(HotbarRoles.roleAt(out, 4));
        assertAllDistinct(out);
    }

    @Test
    void sixClicksOnOneSlotWalkTheWholeCycleBackToEmpty() {
        // Clicking slot 5 six times from the default layout. Each acquisition
        // swaps with the incoming role's holder:
        //   1: empty→WEAPON   w5 r1 p2 f3 b4   (slot 0 goes free)
        //   2: WEAPON→ROD     w1 r5 p2 f3 b4   (swap with slot 1)
        //   3: ROD→POTION     w1 r2 p5 f3 b4   (swap with slot 2)
        //   4: POTION→FOOD    w1 r2 p3 f5 b4   (swap with slot 3)
        //   5: FOOD→BLOCK     w1 r2 p3 f4 b5   (swap with slot 4)
        //   6: BLOCK→empty    w1 r2 p3 f4 b0   (block → lowest free slot = 0)
        Items out = Items.DEFAULT;
        for (int i = 0; i < 6; i++) {
            out = HotbarRoles.cycle(out, 5);
            assertAllDistinct(out);
        }
        assertEquals(1, out.weaponSlot());
        assertEquals(2, out.rodSlot());
        assertEquals(3, out.potSlot());
        assertEquals(4, out.foodSlot());
        assertEquals(0, out.blockSlot());
        assertNull(HotbarRoles.roleAt(out, 5));
    }

    @Test
    void everySingleClickPreservesUniqueness() {
        for (int slot = 0; slot <= 8; slot++) {
            assertAllDistinct(HotbarRoles.cycle(Items.DEFAULT, slot));
        }
    }

    @Test
    void aHandEditedDuplicateSelfHealsOnTheFirstClick() {
        // weapon and rod both on slot 0 — legal for the record, fatal for
        // seedConsumables. Clicking slot 0: roleAt picks WEAPON (first in
        // cycle order); next is ROD, whose holder IS the clicked slot, so the
        // displaced WEAPON heals onto the lowest free slot: 0 is clicked,
        // rod 0 / potion 2 / food 3 / block 4 are taken → slot 1.
        Items duplicated = new Items(false, false, 0, 0, 2, 3, 4, false, false, 0);
        Items out = HotbarRoles.cycle(duplicated, 0);
        assertEquals(1, out.weaponSlot());
        assertEquals(0, out.rodSlot());
        assertAllDistinct(out);
    }

    /** All five role slots pairwise distinct — the seeder's survival condition. */
    private static void assertAllDistinct(Items items) {
        Set<Integer> seen = new HashSet<>();
        for (Role role : Role.values()) {
            int slot = HotbarRoles.slotOf(items, role);
            assertTrue(seen.add(slot),
                    role + " duplicates hotbar slot " + slot + " in " + items);
        }
    }
}

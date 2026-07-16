package me.vexmc.simpleboxer.gui.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link SettingsHubMenu#tileSlots}: for the six shipped categories the
 * computed layout must be byte-identical to the hand-laid grid the hub used
 * before the slots were computed, and growth stays centred up to the loud
 * nine-tile ceiling. Slot arithmetic: a row of tiles starts at
 * {@code base = 18 + row * 9} (the third inventory row), and a full row sits
 * at columns 2/4/6, a pair at 3/5, a single at 4.
 */
class SettingsHubMenuLayoutTest {

    @Test
    void sixCategoriesMatchTheOldHandLaidGrid() {
        // Row 0: base 18 + {2,4,6} = 20,22,24 · row 1: base 27 + {2,4,6} = 29,31,33.
        assertArrayEquals(new int[] {20, 22, 24, 29, 31, 33},
                SettingsHubMenu.tileSlots(6));
    }

    @Test
    void aSeventhCategoryAppearsCentredInsteadOfVanishing() {
        // The old fixed array dropped tile 7 silently; now row 2 (base 36)
        // holds the single leftover at column 4 → 40.
        assertArrayEquals(new int[] {20, 22, 24, 29, 31, 33, 40},
                SettingsHubMenu.tileSlots(7));
    }

    @Test
    void anEighthPairsTheLastRow() {
        // Row 2 leftover pair at columns {3,5} → 39, 41.
        assertArrayEquals(new int[] {20, 22, 24, 29, 31, 33, 39, 41},
                SettingsHubMenu.tileSlots(8));
    }

    @Test
    void nineFillsAllThreeRows() {
        // Row 2 full at columns {2,4,6} → 38, 40, 42.
        assertArrayEquals(new int[] {20, 22, 24, 29, 31, 33, 38, 40, 42},
                SettingsHubMenu.tileSlots(9));
    }

    @Test
    void smallCountsStayCentred() {
        // One tile: row 0 single at column 4 → 22. Two: pair {3,5} → 21, 23.
        assertArrayEquals(new int[] {22}, SettingsHubMenu.tileSlots(1));
        assertArrayEquals(new int[] {21, 23}, SettingsHubMenu.tileSlots(2));
    }

    @Test
    void impossibleCountsFailLoudly() {
        // Rows 2-4 hold at most nine tiles; zero is a broken enum.
        assertThrows(IllegalStateException.class, () -> SettingsHubMenu.tileSlots(0));
        assertThrows(IllegalStateException.class, () -> SettingsHubMenu.tileSlots(10));
    }
}

package me.vexmc.simpleboxer.gui.menu;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * The top-level grouping a {@link SettingDescriptor} belongs to — one tile on
 * the {@link SettingsHubMenu} and one paginated {@link CategoryMenu} behind it.
 * Categories exist only to organise the descriptor list into human-sized
 * screens, cut along user intent ("make potion healing work" is one page, not
 * two); adding a knob to an existing category needs no change here, and a
 * brand-new category really is a single enum constant — the hub computes its
 * tile slots from {@code values().length}.
 */
enum SettingCategory {

    // Ordered the way the hub lays the tiles out, left-to-right, top-to-bottom.
    AIM("Aim & Clicking", Material.ENDER_EYE,
            "§7Ping, CPS, reach, aim cone & spring"),
    COMBAT("Combat", Material.IRON_SWORD,
            "§7Block-hit, rod, s-tap, misses, w-tap"),
    MOVEMENT("Movement", Material.LEATHER_BOOTS,
            "§7Closing style and strafe side-picking"),
    POTIONS("Potions & Healing", Material.SPLASH_POTION,
            "§7The self-heal band and the pot supply"),
    SURVIVAL("Survival", Material.TOTEM_OF_UNDYING,
            "§7Invincibility, death and hunger"),
    ITEMS("Items", Material.CHEST,
            "§7Auto-pickup, loadout lock, hotbar layout");

    private final String title;
    private final Material icon;
    private final String summary;

    SettingCategory(@NotNull String title, @NotNull Material icon, @NotNull String summary) {
        this.title = title;
        this.icon = icon;
        this.summary = summary;
    }

    @NotNull String title() {
        return title;
    }

    @NotNull Material icon() {
        return icon;
    }

    @NotNull String summary() {
        return summary;
    }
}

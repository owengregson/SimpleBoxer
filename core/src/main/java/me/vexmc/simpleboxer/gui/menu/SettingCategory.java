package me.vexmc.simpleboxer.gui.menu;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * The top-level grouping a {@link SettingDescriptor} belongs to — one tile on
 * the {@link SettingsHubMenu} and one paginated {@link CategoryMenu} behind it.
 * Categories exist only to organise the descriptor list into human-sized
 * screens; adding a knob to an existing category needs no change here, and a
 * brand-new category is a single enum constant.
 */
enum SettingCategory {

    // Ordered the way the hub lays the tiles out, left-to-right, top-to-bottom.
    AIM("Aim & Clicking", Material.ENDER_EYE,
            "§7Ping, CPS, reach, aim cone & spring"),
    COMBAT("Combat", Material.IRON_SWORD,
            "§7Block-hit, rod knockback, s-tap, misses"),
    MOVEMENT("Movement", Material.LEATHER_BOOTS,
            "§7How the boxer closes distance"),
    SURVIVAL("Survival", Material.TOTEM_OF_UNDYING,
            "§7Invincibility, death, hunger, self-heal"),
    ITEMS("Items", Material.CHEST,
            "§7Auto-pickup, loadout lock, hotbar slots"),
    WTAP("W-Tap", Material.FEATHER,
            "§7Sprint-reset timing after a landed hit");

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

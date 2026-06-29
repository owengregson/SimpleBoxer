package me.vexmc.simpleboxer.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Everything a button handler needs about one click: who clicked, how, where,
 * and the menu it belongs to (for refresh/navigation). The raw event is
 * exposed for the few menus that read the cursor or the clicked sub-inventory
 * directly (the loadout editor); most handlers only consult {@link #click()}.
 */
public record MenuClick(
        @NotNull Player player,
        @NotNull ClickType click,
        int slot,
        @NotNull Menu menu,
        @NotNull InventoryClickEvent raw) {

    public boolean left() {
        return click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
    }

    public boolean right() {
        return click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
    }

    public boolean shift() {
        return click.isShiftClick();
    }

    /** Re-render the owning menu in place (icons reflect freshly-changed state). */
    public void refresh() {
        menu.refresh();
    }
}

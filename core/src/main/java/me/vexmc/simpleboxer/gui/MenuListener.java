package me.vexmc.simpleboxer.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The single server-wide bridge from Bukkit inventory events to the {@link Menu}
 * that owns the inventory. Identity is by holder: a SimpleBoxer inventory holds
 * itself ({@code Menu implements InventoryHolder}), so a click on any other
 * plugin's GUI — or a chest — falls straight through untouched.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handleDrag(event);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handleClose(event);
        }
    }
}

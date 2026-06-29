package me.vexmc.simpleboxer.gui;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base of every SimpleBoxer menu. A menu IS its own {@link InventoryHolder} —
 * the single {@link MenuListener} identifies our inventories by
 * {@code getHolder() instanceof Menu} and routes the event straight back here,
 * so no registry of open inventories is needed and a foreign plugin's GUI can
 * never be mistaken for ours.
 *
 * <p>The interaction contract is "cancel everything, then opt back in": every
 * raw click and drag is cancelled up front (a menu never lets items be pulled
 * out, shoved in, hot-swapped or dragged across), and behaviour is added only
 * through {@link Button}s in the top inventory or the {@link #onBottomClick}
 * hook a subclass overrides. The loadout editor is the one menu that does more
 * than route buttons, and it still never lets Bukkit move a real item — it
 * mutates a model and re-renders.</p>
 */
@SuppressWarnings("deprecation") // §-string createInventory title: the cross-version overload
public abstract class Menu implements InventoryHolder {

    private final Gui gui;
    private final @Nullable Menu parent;
    private final int rows;
    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();
    private @Nullable Player viewer;

    protected Menu(@NotNull Gui gui, @Nullable Menu parent, int rows, @NotNull String title) {
        this.gui = gui;
        this.parent = parent;
        this.rows = Math.max(1, Math.min(rows, 6));
        // §-coded String title: the common-denominator overload present on
        // every supported version (Component overloads arrived far later).
        this.inventory = Bukkit.createInventory(this, this.rows * 9, title);
    }

    @Override
    public final @NotNull Inventory getInventory() {
        return inventory;
    }

    protected final @NotNull Gui gui() {
        return gui;
    }

    protected final @Nullable Menu parent() {
        return parent;
    }

    protected final @Nullable Player viewer() {
        return viewer;
    }

    protected final int rows() {
        return rows;
    }

    protected final int size() {
        return rows * 9;
    }

    /** Populate the inventory. Called on open and on every {@link #refresh()}. */
    protected abstract void build();

    /* ------------------------------------------------------------------ */
    /*  Rendering helpers                                                  */
    /* ------------------------------------------------------------------ */

    protected final void set(int slot, @NotNull Button button) {
        if (slot < 0 || slot >= size()) {
            return;
        }
        buttons.put(slot, button);
        inventory.setItem(slot, button.icon());
    }

    /** Place a raw stack with no behaviour (used by the loadout slots). */
    protected final void setRaw(int slot, @Nullable org.bukkit.inventory.ItemStack item) {
        if (slot < 0 || slot >= size()) {
            return;
        }
        buttons.remove(slot);
        inventory.setItem(slot, item);
    }

    protected final void clearAll() {
        buttons.clear();
        inventory.clear();
    }

    /** A one-pane frame around the edges of the menu. */
    protected final void border(@NotNull Material pane) {
        org.bukkit.inventory.ItemStack filler = Icon.filler(pane);
        for (int column = 0; column < 9; column++) {
            inventory.setItem(column, filler);
            inventory.setItem((rows - 1) * 9 + column, filler);
        }
        for (int row = 1; row < rows - 1; row++) {
            inventory.setItem(row * 9, filler);
            inventory.setItem(row * 9 + 8, filler);
        }
    }

    /** Fill every still-empty slot with a quiet backing pane. */
    protected final void fillEmpty(@NotNull Material pane) {
        org.bukkit.inventory.ItemStack filler = Icon.filler(pane);
        for (int slot = 0; slot < size(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle + navigation                                             */
    /* ------------------------------------------------------------------ */

    public final void open(@NotNull Player player) {
        this.viewer = player;
        refresh();
        player.openInventory(inventory);
    }

    /** Re-render in place — icons pick up freshly-changed state immediately. */
    public final void refresh() {
        clearAll();
        build();
    }

    /** Re-open the parent menu, or close if this is a root. */
    protected final void back(@NotNull Player player) {
        if (parent != null) {
            parent.open(player);
        } else {
            player.closeInventory();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Event entry points (called by MenuListener)                        */
    /* ------------------------------------------------------------------ */

    final void handleClick(@NotNull InventoryClickEvent event) {
        // Cancel first, unconditionally: nothing a menu does ever lets Bukkit
        // move a real item. Subclasses add behaviour on top of the cancel.
        event.setCancelled(true);
        if (viewer == null || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return; // clicked outside the window
        }
        if (clicked.equals(inventory)) {
            Button button = buttons.get(event.getRawSlot());
            if (button != null) {
                button.click(new MenuClick((Player) event.getWhoClicked(),
                        event.getClick(), event.getRawSlot(), this, event));
            } else {
                onTopClick(event);
            }
        } else {
            onBottomClick(event);
        }
    }

    final void handleDrag(@NotNull InventoryDragEvent event) {
        event.setCancelled(true);
        onDrag(event);
    }

    final void handleClose(@NotNull InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            onClose(player);
        }
    }

    /** A click on a top-inventory slot with no button. Default: nothing. */
    protected void onTopClick(@NotNull InventoryClickEvent event) {
    }

    /** A click in the player's own inventory while the menu is open. */
    protected void onBottomClick(@NotNull InventoryClickEvent event) {
    }

    /** A drag (already cancelled). */
    protected void onDrag(@NotNull InventoryDragEvent event) {
    }

    /** The menu was closed (navigation or escape). Default: nothing. */
    protected void onClose(@NotNull Player player) {
    }
}

package me.vexmc.simpleboxer.gui.menu;

import java.util.List;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A six-row list screen with a header row, a 36-slot content grid, and a
 * navigation footer (back · page · close). Subclasses supply the live item
 * list and how to render one cell; paging, the footer, and the background are
 * handled here so every list in the plugin behaves identically.
 */
abstract class PaginatedMenu<T> extends Menu {

    private static final int CONTENT_START = 9;
    private static final int PER_PAGE = 36;

    private int page;

    protected PaginatedMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull String title) {
        super(gui, parent, 6, title);
    }

    /** The live items to lay out — re-read on every render so the list is current. */
    protected abstract @NotNull List<T> items();

    /** The button for one item. */
    protected abstract @NotNull Button render(@NotNull T item);

    /** The header plate shown at the top-centre (slot 4). */
    protected abstract @NotNull org.bukkit.inventory.ItemStack header(int itemCount);

    /** Extra footer buttons a subclass wants (slots 46, 47, 51, 52 are free). */
    protected void footer() {
    }

    @Override
    protected final void build() {
        List<T> all = items();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        set(4, Button.display(header(all.size())));

        int from = page * PER_PAGE;
        int to = Math.min(from + PER_PAGE, all.size());
        for (int i = from; i < to; i++) {
            set(CONTENT_START + (i - from), render(all.get(i)));
        }

        // Footer: back (45) · prev (48) · page indicator (49) · next (50) · close (53).
        set(45, Button.of(MenuParts.back(), click -> back(click.player())));
        if (page > 0) {
            set(48, Button.of(Icon.of(Material.SPECTRAL_ARROW).name("§e« Previous page").build(),
                    click -> {
                        page--;
                        click.refresh();
                    }));
        }
        set(49, Button.display(Icon.of(Material.PAPER)
                .name("§7Page §f" + (page + 1) + "§7/§f" + pages)
                .lore("§8" + all.size() + " total").build()));
        if (page < pages - 1) {
            set(50, Button.of(Icon.of(Material.SPECTRAL_ARROW).name("§eNext page »").build(),
                    click -> {
                        page++;
                        click.refresh();
                    }));
        }
        set(53, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        footer();
        fillEmpty(MenuParts.BACKGROUND);
    }
}

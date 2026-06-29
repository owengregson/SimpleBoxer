package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin-wide settings — the handful of options that aren't per-boxer: the
 * tab-list concealment policy and a config reload. Both persist immediately.
 */
final class PluginSettingsMenu extends Menu {

    PluginSettingsMenu(@NotNull Gui gui, @Nullable Menu parent) {
        super(gui, parent, 3, "§8Plugin Settings");
    }

    @Override
    protected void build() {
        boolean hide = gui().config().snapshot().hideFromTab();

        set(4, Button.display(Icon.of(Material.COMPARATOR).glow()
                .name("§6§lPlugin Settings").build()));

        set(11, Button.of(Icon.of(Material.PAPER).glow(hide)
                        .name("§b§lHide from tab list")
                        .lore("§7State: " + MenuParts.onOff(hide),
                                "§8Keep boxers off the player tab list.",
                                "§8(They stay tab-completable in commands.)",
                                "§8Applies to boxers spawned after the change.",
                                "",
                                "§8» §7Click to toggle").build(),
                click -> {
                    gui().config().setHideFromTab(!hide);
                    click.refresh();
                }));

        set(15, Button.of(Icon.of(Material.WRITABLE_BOOK)
                        .name("§b§lReload config.yml")
                        .lore("§7Re-read the config from disk.",
                                "",
                                "§eClick to reload").build(),
                click -> {
                    gui().config().reload();
                    click.player().sendMessage("§aSimpleBoxer configuration reloaded.");
                    click.refresh();
                }));

        set(18, Button.of(MenuParts.back(), click -> back(click.player())));
        set(26, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }
}

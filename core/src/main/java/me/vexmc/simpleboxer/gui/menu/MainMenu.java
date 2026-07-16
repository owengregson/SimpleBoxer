package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The root hub — the screen {@code /boxer} opens. Four doors: spawn a boxer,
 * manage the live ones, edit behaviour presets and defaults, and the plugin
 * settings. Everything the old command tree did is reachable from here without
 * typing a thing.
 */
public final class MainMenu extends Menu {

    public MainMenu(@NotNull Gui gui, @Nullable Menu parent) {
        super(gui, parent, 5, "§8§lSimpleBoxer");
    }

    @Override
    protected void build() {
        int live = gui().manager().all().size();

        set(4, Button.display(Icon.of(Material.NETHER_STAR).glow()
                .name("§6§lSimpleBoxer")
                .lore("§7Virtual sparring players —",
                        "§7real bots you spawn, kit out, and tune.",
                        "",
                        "§8" + live + " boxer(s) live right now").build()));

        set(20, Button.of(Icon.of(Material.ARMOR_STAND)
                        .name("§a§lSpawn a Boxer")
                        .lore("§7Build and drop a new sparring partner:",
                                "§7name, difficulty, skin, target and kit.",
                                "",
                                "§eClick to open").build(),
                click -> new SpawnMenu(gui(), this, new SpawnDraft()).open(click.player())));

        set(22, Button.of(Icon.of(Material.PLAYER_HEAD, Math.max(1, live))
                        .name("§e§lManage Boxers")
                        .lore("§7" + live + " live — control, retune, re-kit,",
                                "§7target, teleport or remove each one.",
                                "",
                                "§eClick to open").build(),
                click -> new BoxerListMenu(gui(), this).open(click.player())));

        set(24, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
                        .name("§b§lPresets & Defaults")
                        .lore("§7Edit the spawn defaults and the named",
                                "§7difficulty presets — saved to config.",
                                "",
                                "§eClick to open").build(),
                click -> new PresetsMenu(gui(), this).open(click.player())));

        set(31, Button.of(Icon.of(Material.COMPARATOR)
                        .name("§d§lPlugin Settings")
                        .lore("§7Tab-list visibility and config reload.",
                                "",
                                "§eClick to open").build(),
                click -> new PluginSettingsMenu(gui(), this).open(click.player())));

        set(40, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }
}

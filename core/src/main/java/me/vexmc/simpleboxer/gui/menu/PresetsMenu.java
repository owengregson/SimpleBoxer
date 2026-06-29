package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsWriter;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Edit the persistent behaviour config: the spawn {@code defaults} and every
 * named preset. Left-click a preset to tune it (saved to {@code config.yml});
 * right-click a custom one to delete it. The footer adds the defaults editor
 * and a "create preset" button.
 */
final class PresetsMenu extends PaginatedMenu<String> {

    PresetsMenu(@NotNull Gui gui, @Nullable Menu parent) {
        super(gui, parent, "§8Presets & Defaults");
    }

    @Override
    protected @NotNull List<String> items() {
        return new ArrayList<>(gui().config().snapshot().presetNames());
    }

    @Override
    protected @NotNull ItemStack header(int itemCount) {
        return Icon.of(Material.ENCHANTED_BOOK).glow().name("§6§lPresets & Defaults")
                .lore("§7" + itemCount + " preset(s)",
                        "§8Left-click: edit · Right-click: delete custom").build();
    }

    @Override
    protected @NotNull Button render(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        boolean custom = gui().config().isFileBacked(name);
        Icon icon = Icon.of(custom ? Material.WRITABLE_BOOK : Material.ENCHANTED_BOOK)
                .glow(!custom).name("§b" + name + (custom ? " §8(custom)" : " §8(built-in)"));
        if (preset != null) {
            String aim = BoxerSettingsWriter.aimPresetName(preset.aim());
            icon.lore("§7ping §f" + preset.pingMs() + "ms§7, cps §f" + MenuParts.number(preset.cps()),
                    "§7aim §f" + (aim == null ? "custom" : aim)
                            + "§7, move §f" + MenuParts.prettyStyle(preset.movement().style()),
                    "",
                    "§eLeft-click §7to edit",
                    custom ? "§cRight-click §7to delete" : "§8(built-in — cannot delete)");
        }
        return Button.of(icon.build(), click -> {
            if (click.right() && custom) {
                new ConfirmMenu(gui(), this, "Delete preset", Material.WRITABLE_BOOK,
                        "Delete preset '" + name + "'?",
                        ConfirmMenu.lines("§7Its config entry is removed."),
                        player -> {
                            gui().config().deletePreset(name);
                            player.sendMessage("§aDeleted preset §f" + name + "§a.");
                            open(player);
                        }).open(click.player());
            } else {
                new SettingsMenu(gui(), this, SettingsTarget.forPreset(gui(), name))
                        .open(click.player());
            }
        });
    }

    @Override
    protected void footer() {
        set(46, Button.of(Icon.of(Material.NETHER_STAR).glow()
                        .name("§a§lEdit Defaults")
                        .lore("§7The settings every un-preset spawn uses.",
                                "",
                                "§eClick to edit").build(),
                click -> new SettingsMenu(gui(), this, SettingsTarget.forDefaults(gui()))
                        .open(click.player())));

        set(47, Button.of(Icon.of(Material.WRITABLE_BOOK)
                        .name("§a§lCreate Preset")
                        .lore("§7Start a new preset from the current defaults.",
                                "",
                                "§eClick to name it").build(),
                click -> gui().prompts().prompt(click.player(),
                        "Type a name for the new preset:",
                        input -> createPreset(input, click.player()),
                        () -> open(click.player()))));
    }

    private void createPreset(@NotNull String rawName, @NotNull org.bukkit.entity.Player player) {
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            player.sendMessage("§cPreset names are 1-32 chars of letters, digits, - or _.");
            open(player);
            return;
        }
        gui().config().savePreset(name, gui().config().snapshot().defaults());
        player.sendMessage("§aCreated preset §f" + name + "§a — now tuning it.");
        new SettingsMenu(gui(), this, SettingsTarget.forPreset(gui(), name)).open(player);
    }
}

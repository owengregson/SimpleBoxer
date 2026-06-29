package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
 * Pick a behaviour preset by name — the difficulty ladder plus any custom
 * presets. The callback receives the chosen key, or {@code null} for the
 * "defaults" entry when the screen offers it (spawning a boxer with no preset
 * means "use the configured defaults").
 */
final class PresetPickerMenu extends PaginatedMenu<String> {

    private final String heading;
    private final boolean includeDefaults;
    private final Consumer<@Nullable String> onPick;

    PresetPickerMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull String heading,
            boolean includeDefaults, @NotNull Consumer<@Nullable String> onPick) {
        super(gui, parent, "§8Choose a preset");
        this.heading = heading;
        this.includeDefaults = includeDefaults;
        this.onPick = onPick;
    }

    @Override
    protected @NotNull List<String> items() {
        return new ArrayList<>(gui().config().snapshot().presetNames());
    }

    @Override
    protected @NotNull ItemStack header(int itemCount) {
        return Icon.of(Material.ENCHANTED_BOOK).glow().name("§6§l" + heading)
                .lore("§7" + itemCount + " preset(s) available").build();
    }

    @Override
    protected @NotNull Button render(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        Icon icon = Icon.of(Material.WRITABLE_BOOK).name("§b" + name);
        if (preset != null) {
            String aim = BoxerSettingsWriter.aimPresetName(preset.aim());
            icon.lore(
                    "§7ping §f" + preset.pingMs() + "ms§7, cps §f" + MenuParts.number(preset.cps()),
                    "§7aim §f" + (aim == null ? "custom" : aim)
                            + "§7, move §f" + MenuParts.prettyStyle(preset.movement().style()),
                    "",
                    "§eClick to choose");
        }
        return Button.of(icon.build(), click -> onPick.accept(name));
    }

    @Override
    protected void footer() {
        if (includeDefaults) {
            set(47, Button.of(Icon.of(Material.NETHER_STAR).glow()
                            .name("§fDefaults").lore("§7Use the configured defaults").build(),
                    click -> onPick.accept(null)));
        }
    }
}

package me.vexmc.simpleboxer.gui.menu;

import java.util.Locale;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsWriter;
import me.vexmc.simpleboxer.gui.Icon;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The shared visual language and a handful of value-formatting helpers, kept
 * in one place so every screen reads the same: a black-glass background, an
 * arrow for "back", a barrier for "close", and consistent §-colour roles
 * (gold titles, green values, gray hints).
 */
final class MenuParts {

    private MenuParts() {}

    /** Full-window background — placed under every screen for a sleek frame. */
    static final Material BACKGROUND = Material.BLACK_STAINED_GLASS_PANE;

    static @NotNull ItemStack back() {
        return Icon.of(Material.ARROW).name("§7« §fBack").build();
    }

    static @NotNull ItemStack close() {
        return Icon.of(Material.BARRIER).name("§c✖ Close").build();
    }

    /* ---- value formatting --------------------------------------------- */

    static @NotNull String onOff(boolean value) {
        return value ? "§a✔ Enabled" : "§c✘ Disabled";
    }

    static @NotNull String number(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static @NotNull String prettyStyle(@NotNull BoxerSettings.Movement.Style style) {
        return BoxerSettingsWriter.styleName(style);
    }

    /** A green-vs-gray hint line that reads click bindings the same everywhere. */
    static @NotNull String adjustHint(@NotNull String left, @NotNull String right,
            @NotNull String shift) {
        return "§8» §7Left: §f" + left + "  §7Right: §f" + right + "  §7Shift: §f" + shift;
    }

    /**
     * Step a number by click: left adds, right subtracts, holding shift uses
     * the bigger step — the convention every numeric setting icon advertises.
     * Result is clamped to {@code [min, max]} and rounded to kill float drift.
     */
    static double step(double current, @NotNull ClickType click, double small, double big,
            double min, double max) {
        double delta = click.isShiftClick() ? big : small;
        boolean add = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
        double next = add ? current + delta : current - delta;
        next = Math.max(min, Math.min(max, next));
        // Snap to a thousandth so repeated ±0.05 steps don't accumulate 0.30000004.
        return Math.round(next * 1000.0) / 1000.0;
    }

    static int stepInt(int current, @NotNull ClickType click, int small, int big, int min, int max) {
        int delta = click.isShiftClick() ? big : small;
        boolean add = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
        int next = add ? current + delta : current - delta;
        return Math.max(min, Math.min(max, next));
    }
}

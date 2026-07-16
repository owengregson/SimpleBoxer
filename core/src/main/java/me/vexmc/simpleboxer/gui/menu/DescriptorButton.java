package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

/**
 * Turns one {@link SettingDescriptor} into a live {@link Button}, reusing the
 * exact numeric-step / toggle / cycle idioms the old hand-laid settings screen
 * used — the difference is that here the behaviour comes from the descriptor,
 * not from a slot-specific handler. This is the single place a knob's on-screen
 * shape lives, so every category renders identically and a new knob inherits
 * the whole interaction for free.
 *
 * <p>All mutation flows through {@link SettingsTarget#apply}, which retunes a
 * live boxer or writes {@code config.yml} depending on scope. Every icon is
 * built through {@link Icon} (cross-version safe), and the click never lets
 * Bukkit move a real item — {@link Menu} has already cancelled it.</p>
 */
final class DescriptorButton {

    private DescriptorButton() {}

    static @NotNull Button build(@NotNull Gui gui, @NotNull Menu owner,
            @NotNull SettingsTarget target, @NotNull SettingDescriptor descriptor) {
        BoxerSettings snapshot = target.settings();
        return switch (descriptor.kind()) {
            case TOGGLE -> toggleButton(target, descriptor, snapshot);
            case NUMERIC -> numericButton(gui, owner, target, descriptor, snapshot);
            case CYCLE -> cycleButton(target, descriptor, snapshot);
        };
    }

    /* ------------------------------------------------------------------ */
    /*  Per-kind renderers                                                 */
    /* ------------------------------------------------------------------ */

    private static @NotNull Button toggleButton(@NotNull SettingsTarget target,
            @NotNull SettingDescriptor d, @NotNull BoxerSettings s) {
        boolean enabled = d.enabledFor(s);
        boolean on = d.state().test(s);
        List<String> lore = new ArrayList<>();
        lore.add("§7State: " + MenuParts.onOff(on));
        if (!enabled) {
            lore.add("§8requires " + d.requiresLabel());
        }
        lore.addAll(d.help());
        lore.add("");
        lore.add("§8» §7Click to toggle");
        Icon icon = shell(d, enabled).glow(on && enabled).lore(lore);
        return Button.of(icon.build(), click -> {
            apply(target, d.toggle().apply(target.settings()));
            click.refresh();
        });
    }

    private static @NotNull Button numericButton(@NotNull Gui gui, @NotNull Menu owner,
            @NotNull SettingsTarget target, @NotNull SettingDescriptor d, @NotNull BoxerSettings s) {
        boolean enabled = d.enabledFor(s);
        double value = d.number().applyAsDouble(s);
        List<String> lore = new ArrayList<>();
        lore.add("§7Value: §a" + MenuParts.number(value) + d.unit());
        if (!enabled) {
            lore.add("§8requires " + d.requiresLabel());
        }
        lore.addAll(d.help());
        lore.add("");
        lore.add(MenuParts.adjustHint("+" + trim(d.small()), "-" + trim(d.small()),
                "±" + trim(d.big())));
        lore.add("§8» §7Q: §ftype a value");
        Icon icon = shell(d, enabled).lore(lore);
        return Button.of(icon.build(), click -> {
            if (isDrop(click.click())) {
                promptNumber(gui, owner, target, d, click.player());
                return;
            }
            double current = d.number().applyAsDouble(target.settings());
            double next = d.integer()
                    ? MenuParts.stepInt((int) Math.round(current), click.click(),
                            (int) d.small(), (int) d.big(), (int) d.min(), (int) d.max())
                    : MenuParts.step(current, click.click(), d.small(), d.big(), d.min(), d.max());
            apply(target, d.setNumber().apply(target.settings(), next));
            click.refresh();
        });
    }

    private static @NotNull Button cycleButton(@NotNull SettingsTarget target,
            @NotNull SettingDescriptor d, @NotNull BoxerSettings s) {
        boolean enabled = d.enabledFor(s);
        int index = currentIndex(d, s);
        String label = index >= 0 ? d.options().get(index).label() : "§o" + d.customLabel();
        List<String> lore = new ArrayList<>();
        lore.add("§7Value: §a" + label);
        if (!enabled) {
            lore.add("§8requires " + d.requiresLabel());
        }
        lore.addAll(d.help());
        lore.add("");
        lore.add("§8» §7Left: §fnext  §7Right: §fprevious");
        Icon icon = shell(d, enabled).lore(lore);
        return Button.of(icon.build(), click -> {
            BoxerSettings current = target.settings();
            List<SettingDescriptor.CycleOption> options = d.options();
            int count = options.size();
            int at = currentIndex(d, current);
            int next;
            if (at < 0) {
                // Off the known set (a custom aim spring): step onto an end.
                next = click.left() ? 0 : count - 1;
            } else {
                next = click.left() ? (at + 1) % count : (at - 1 + count) % count;
            }
            apply(target, options.get(next).select().apply(current));
            click.refresh();
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The tile shell every kind shares. A dependent knob whose master toggle
     * is off renders dimmed — gray dye, gray name — so a page reads at a
     * glance which values are currently inert; the click stays live, because
     * pre-configuring a dependent value before flipping its master on is
     * useful, not an error.
     */
    private static @NotNull Icon shell(@NotNull SettingDescriptor d, boolean enabled) {
        return Icon.of(enabled ? d.material() : Material.GRAY_DYE).clean()
                .name((enabled ? "§b§l" : "§7§l") + d.name());
    }

    /**
     * Push a new profile through the target. A clamp guards every adjuster and
     * cross-field constraints (rod band, heal band) are enforced in the apply
     * lambdas, so a rejection here is purely defensive.
     */
    private static void apply(@NotNull SettingsTarget target, @NotNull BoxerSettings next) {
        try {
            target.apply(next);
        } catch (IllegalArgumentException rejected) {
            // Left intentionally silent — the value simply doesn't change.
        }
    }

    private static int currentIndex(@NotNull SettingDescriptor d, @NotNull BoxerSettings s) {
        List<SettingDescriptor.CycleOption> options = d.options();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).matches().test(s)) {
                return i;
            }
        }
        return -1;
    }

    private static void promptNumber(@NotNull Gui gui, @NotNull Menu owner,
            @NotNull SettingsTarget target, @NotNull SettingDescriptor d, @NotNull Player player) {
        gui.prompts().prompt(player,
                "Enter " + d.name() + " (§f" + trim(d.min()) + "§7–§f" + trim(d.max()) + "§7):",
                input -> {
                    try {
                        double parsed = Double.parseDouble(input.trim());
                        double clamped = Math.max(d.min(), Math.min(d.max(), parsed));
                        if (d.integer()) {
                            clamped = Math.rint(clamped);
                        }
                        apply(target, d.setNumber().apply(target.settings(), clamped));
                    } catch (NumberFormatException notANumber) {
                        player.sendMessage("§cThat wasn't a number: §f" + input);
                    }
                    owner.open(player);
                },
                () -> owner.open(player));
    }

    private static boolean isDrop(@NotNull ClickType click) {
        // Q / Ctrl-Q — the one keybind that fires in every game mode (middle-click
        // is creative-only), so typed entry is reachable in survival too.
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP;
    }

    /** A step/bound as a compact label: {@code 10 → "10"}, {@code 0.05 → "0.05"}. */
    private static @NotNull String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.3f", value);
        // Drop trailing zeros then a dangling point so 0.100 reads as 0.1.
        text = text.indexOf('.') < 0 ? text : text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }
}

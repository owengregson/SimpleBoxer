package me.vexmc.simpleboxer.gui;

import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One clickable cell in a {@link Menu}: the icon shown and the action its
 * click runs. A {@code null} handler is a pure decoration (border panes,
 * headers, status read-outs) — its click is swallowed and nothing happens.
 */
public final class Button {

    private final ItemStack icon;
    private final @Nullable Consumer<MenuClick> onClick;

    private Button(@NotNull ItemStack icon, @Nullable Consumer<MenuClick> onClick) {
        this.icon = icon;
        this.onClick = onClick;
    }

    /** A clickable button. */
    public static @NotNull Button of(@NotNull ItemStack icon, @NotNull Consumer<MenuClick> onClick) {
        return new Button(icon, onClick);
    }

    /** A non-interactive display cell. */
    public static @NotNull Button display(@NotNull ItemStack icon) {
        return new Button(icon, null);
    }

    public @NotNull ItemStack icon() {
        return icon;
    }

    void click(@NotNull MenuClick click) {
        if (onClick != null) {
            onClick.accept(click);
        }
    }

    boolean interactive() {
        return onClick != null;
    }
}

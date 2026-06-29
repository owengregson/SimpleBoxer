package me.vexmc.simpleboxer.gui.menu;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable yes/no gate for the irreversible actions (removing a boxer,
 * removing them all, deleting a preset). Cancel returns to the parent screen;
 * confirm runs the supplied action, which is responsible for any navigation
 * afterwards.
 */
final class ConfirmMenu extends Menu {

    private final String prompt;
    private final List<String> detail;
    private final Material subject;
    private final Consumer<Player> onConfirm;

    ConfirmMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull String title,
            @NotNull Material subject, @NotNull String prompt,
            @NotNull List<String> detail, @NotNull Consumer<Player> onConfirm) {
        super(gui, parent, 3, "§8" + title);
        this.subject = subject;
        this.prompt = prompt;
        this.detail = detail;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void build() {
        String[] lore = detail.toArray(new String[0]);
        set(4, Button.display(Icon.of(subject).name("§e" + prompt).lore(lore).build()));

        set(11, Button.of(Icon.of(Material.LIME_CONCRETE).glow()
                        .name("§a§l✔ Confirm").lore("§7Click to proceed").build(),
                click -> onConfirm.accept(click.player())));
        set(15, Button.of(Icon.of(Material.RED_CONCRETE)
                        .name("§c§l✘ Cancel").lore("§7Go back, nothing changes").build(),
                click -> back(click.player())));

        fillEmpty(MenuParts.BACKGROUND);
    }

    static @NotNull List<String> lines(@NotNull String... lines) {
        return Arrays.asList(lines);
    }
}

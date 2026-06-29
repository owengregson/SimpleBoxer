package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pick an online player from a grid of heads — the choice behind "set target"
 * and any other player selection. The chosen player (or {@code null} when the
 * optional "no one" footer button is used) is handed to the callback, which
 * owns what happens next.
 */
final class PlayerPickerMenu extends PaginatedMenu<Player> {

    private final String heading;
    private final boolean allowNone;
    private final Consumer<@Nullable Player> onPick;

    PlayerPickerMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull String heading,
            boolean allowNone, @NotNull Consumer<@Nullable Player> onPick) {
        super(gui, parent, "§8Choose a player");
        this.heading = heading;
        this.allowNone = allowNone;
        this.onPick = onPick;
    }

    @Override
    protected @NotNull List<Player> items() {
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    @Override
    protected @NotNull ItemStack header(int itemCount) {
        return Icon.of(Material.COMPASS).glow().name("§6§l" + heading)
                .lore("§7" + itemCount + " player(s) online",
                        "§8Click a head to choose").build();
    }

    @Override
    protected @NotNull Button render(@NotNull Player player) {
        boolean boxer = gui().manager().isBoxer(player.getUniqueId());
        return Button.of(Icon.head(player)
                        .name("§f" + player.getName())
                        .lore(boxer ? "§8(boxer)" : "§7Click to choose").build(),
                click -> onPick.accept(player));
    }

    @Override
    protected void footer() {
        if (allowNone) {
            set(47, Button.of(Icon.of(Material.BARRIER).name("§eNo one §7(clear)").build(),
                    click -> onPick.accept(null)));
        }
    }
}

package me.vexmc.simpleboxer.gui;

import me.vexmc.simpleboxer.boxer.BoxerManager;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.config.ConfigStore;
import me.vexmc.simpleboxer.gui.menu.MainMenu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * The menu system's service hub: the one object every menu is handed, bundling
 * the boxer registry, the config store, the scheduling seam, and the chat-input
 * helper. It is also the single public entry point the rest of the plugin uses
 * to raise the GUI — {@code /boxer} (and the GUI permission) opens
 * {@link #openMain(Player)} and the player never needs to type another command.
 */
public final class Gui {

    private final JavaPlugin plugin;
    private final BoxerManager manager;
    private final ConfigStore config;
    private final Scheduling scheduling;
    private final ChatPrompts prompts;

    public Gui(@NotNull JavaPlugin plugin, @NotNull BoxerManager manager,
            @NotNull ConfigStore config, @NotNull Scheduling scheduling,
            @NotNull ChatPrompts prompts) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.scheduling = scheduling;
        this.prompts = prompts;
    }

    public @NotNull JavaPlugin plugin() {
        return plugin;
    }

    public @NotNull BoxerManager manager() {
        return manager;
    }

    public @NotNull ConfigStore config() {
        return config;
    }

    public @NotNull Scheduling scheduling() {
        return scheduling;
    }

    public @NotNull ChatPrompts prompts() {
        return prompts;
    }

    /** Open the root hub for a player. */
    public void openMain(@NotNull Player player) {
        new MainMenu(this, null).open(player);
    }
}

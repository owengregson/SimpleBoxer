package me.vexmc.simpleboxer;

import me.vexmc.simpleboxer.api.BoxerService;
import me.vexmc.simpleboxer.boxer.BoxerManager;
import me.vexmc.simpleboxer.boxer.CombatFeedbackListener;
import me.vexmc.simpleboxer.command.BoxerCommands;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.config.ConfigStore;
import me.vexmc.simpleboxer.guard.SurvivalGuards;
import me.vexmc.simpleboxer.identity.BoxerJoinListener;
import me.vexmc.simpleboxer.platform.BukkitScheduling;
import me.vexmc.simpleboxer.platform.FoliaScheduling;
import me.vexmc.simpleboxer.platform.FoliaSupport;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Virtual sparring players. Boxers are real {@code ServerPlayer}s — present
 * in the player list, targetable by any command, holding real inventories —
 * driven by an in-process client brain that perceives the world through a
 * configurable latency line, aims through a spring model, and moves through
 * the same client physics a vanilla player runs. The design goal is strict:
 * no property of a boxer may make it take knockback or hit registration any
 * differently than a real player with identical state.
 */
public final class SimpleBoxerPlugin extends JavaPlugin {

    private Scheduling scheduling;
    private ConfigStore configStore;
    private BoxerManager boxerManager;

    @Override
    public void onEnable() {
        try {
            this.scheduling = FoliaSupport.isFolia()
                    ? new FoliaScheduling(this)
                    : new BukkitScheduling(this);
        } catch (ReflectiveOperationException foliaApiMissing) {
            getLogger().severe("Folia detected but its scheduler API is not resolvable: "
                    + foliaApiMissing);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.configStore = new ConfigStore(this);
        try {
            this.boxerManager = new BoxerManager(this, scheduling, configStore);
        } catch (ReflectiveOperationException incompatible) {
            getLogger().severe("This server version is not supported: " + incompatible);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getServicesManager().register(
                BoxerService.class, boxerManager, this, ServicePriority.Normal);
        PluginCommand boxerCommand = getCommand("boxer");
        if (boxerCommand != null) {
            BoxerCommands executor = new BoxerCommands(boxerManager, configStore);
            boxerCommand.setExecutor(executor);
            boxerCommand.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(
                new SurvivalGuards(boxerManager, scheduling), this);
        getServer().getPluginManager().registerEvents(
                new BoxerJoinListener(boxerManager, boxerManager.tabConcealer(), scheduling), this);
        getServer().getPluginManager().registerEvents(
                new CombatFeedbackListener(boxerManager), this);
        getLogger().info("SimpleBoxer " + getDescription().getVersion()
                + " enabled (scheduling: " + scheduling.describe() + ").");
    }

    @Override
    public void onDisable() {
        if (boxerManager != null) {
            boxerManager.shutdown();
        }
        getLogger().info("SimpleBoxer disabled.");
    }

    public @NotNull Scheduling scheduling() {
        if (scheduling == null) {
            throw new IllegalStateException("SimpleBoxer is not enabled");
        }
        return scheduling;
    }

    public @NotNull BoxerService boxers() {
        if (boxerManager == null) {
            throw new IllegalStateException("SimpleBoxer is not enabled");
        }
        return boxerManager;
    }
}

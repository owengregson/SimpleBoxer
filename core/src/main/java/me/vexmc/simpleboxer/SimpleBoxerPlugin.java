package me.vexmc.simpleboxer;

import me.vexmc.simpleboxer.api.BoxerService;
import me.vexmc.simpleboxer.boxer.BoxerManager;
import me.vexmc.simpleboxer.boxer.CombatFeedbackListener;
import me.vexmc.simpleboxer.boxer.DeathPolicyGuard;
import me.vexmc.simpleboxer.boxer.ExternalVelocityListener;
import me.vexmc.simpleboxer.boxer.HungerGuard;
import me.vexmc.simpleboxer.boxer.InvincibilityGuard;
import me.vexmc.simpleboxer.boxer.KnockbackListener;
import me.vexmc.simpleboxer.boxer.PickupListener;
import me.vexmc.simpleboxer.command.BoxerCommands;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.config.ConfigStore;
import me.vexmc.simpleboxer.gui.ChatPrompts;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.MenuListener;
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
    private Gui gui;

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

        // The GUI is the front door: /boxer opens it, and every spawn, tune,
        // target, kit and config edit is reachable without typing a command.
        ChatPrompts chatPrompts = new ChatPrompts(scheduling);
        this.gui = new Gui(this, boxerManager, configStore, scheduling, chatPrompts);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);

        PluginCommand boxerCommand = getCommand("boxer");
        if (boxerCommand != null) {
            BoxerCommands executor = new BoxerCommands(boxerManager, configStore, gui);
            boxerCommand.setExecutor(executor);
            boxerCommand.setTabCompleter(executor);
        }
        // The survival guards, decomposed: proper (burst-proof) invincibility,
        // death policy (drops + manual/auto respawn), hunger, and item pickup.
        getServer().getPluginManager().registerEvents(
                new InvincibilityGuard(boxerManager, scheduling), this);
        getServer().getPluginManager().registerEvents(
                new DeathPolicyGuard(boxerManager), this);
        getServer().getPluginManager().registerEvents(
                new HungerGuard(boxerManager, scheduling), this);
        getServer().getPluginManager().registerEvents(
                new PickupListener(boxerManager), this);
        // The unified external-velocity capture: StarEnchants' setVelocity,
        // Mental's delivery, and vanilla player knockback all arrive here and feed
        // the boxer's knockback resolver (which dedups them against the wire).
        getServer().getPluginManager().registerEvents(
                new ExternalVelocityListener(boxerManager), this);
        getServer().getPluginManager().registerEvents(
                new BoxerJoinListener(boxerManager, boxerManager.tabConcealer(), scheduling), this);
        getServer().getPluginManager().registerEvents(
                new CombatFeedbackListener(boxerManager), this);
        // Where the region entity-ticks boxers (Folia), capture their knockback
        // from EntityKnockbackEvent — the poll loses its horizontal component to
        // the region's own tick (the boxer "pops up" with no push).
        if (boxerManager.eventBasedKnockback()) {
            new KnockbackListener(boxerManager, getLogger()).register(this);
        }
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

    /** The in-game menu system — {@code openMain(player)} raises the root hub. */
    public @NotNull Gui gui() {
        if (gui == null) {
            throw new IllegalStateException("SimpleBoxer is not enabled");
        }
        return gui;
    }
}

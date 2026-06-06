package me.vexmc.simpleboxer;

import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.platform.BukkitScheduling;
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

    @Override
    public void onEnable() {
        this.scheduling = new BukkitScheduling(this);
        getLogger().info("SimpleBoxer " + getDescription().getVersion()
                + " enabled (scheduling: " + scheduling.describe() + ").");
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleBoxer disabled.");
    }

    public @NotNull Scheduling scheduling() {
        if (scheduling == null) {
            throw new IllegalStateException("SimpleBoxer is not enabled");
        }
        return scheduling;
    }
}

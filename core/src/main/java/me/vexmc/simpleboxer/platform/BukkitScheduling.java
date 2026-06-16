package me.vexmc.simpleboxer.platform;

import java.time.Duration;
import java.util.Objects;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Classic single-threaded scheduling: every non-Folia server, 1.17 through
 * current. "Global", "at location" and "on entity" all collapse onto the main
 * thread; entity targeting still honors the retired callback so callers get
 * identical semantics when a region-threaded implementation lands.
 */
public final class BukkitScheduling implements Scheduling {

    private final Plugin plugin;

    public BukkitScheduling(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public boolean autoTicksEntities() {
        // Classic servers never entity-tick a clientless boxer; the brain owns its doTick.
        return false;
    }

    @Override
    public boolean ownsRegion(@NotNull Location location) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (live(entity)) {
                task.run();
            } else {
                retired.run();
            }
        });
    }

    /**
     * Players retire on QUIT, not on death — isValid() drops while dead,
     * but a dead player respawns and its work must keep flowing.
     */
    private static boolean live(Entity entity) {
        if (entity instanceof Player player) {
            return player.isOnline();
        }
        return entity.isValid();
    }

    @Override
    public void runLaterOn(@NotNull Entity entity, long delayTicks,
            @NotNull Runnable task, @NotNull Runnable retired) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (live(entity)) {
                task.run();
            } else {
                retired.run();
            }
        }, delayTicks);
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
        return new BukkitHandle(Bukkit.getScheduler().runTaskTimer(plugin, task, initialTicks, periodTicks));
    }

    @Override
    public @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired) {
        BukkitHandle[] holder = new BukkitHandle[1];
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (live(entity)) {
                task.run();
                return;
            }
            holder[0].cancel();
            retired.run();
        }, initialTicks, periodTicks);
        holder[0] = new BukkitHandle(bukkitTask);
        return holder[0];
    }

    @Override
    public @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task) {
        long initialTicks = toTicks(initial);
        long periodTicks = Math.max(1, toTicks(period));
        return new BukkitHandle(
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialTicks, periodTicks));
    }

    @Override
    public @NotNull String describe() {
        return "bukkit";
    }

    private static long toTicks(Duration duration) {
        return Math.max(0, duration.toMillis() / 50);
    }

    private record BukkitHandle(@NotNull BukkitTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean cancelled() {
            return task.isCancelled();
        }
    }
}

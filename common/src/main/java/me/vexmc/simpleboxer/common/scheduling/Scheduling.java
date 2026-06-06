package me.vexmc.simpleboxer.common.scheduling;

import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * The one scheduling surface SimpleBoxer code is allowed to touch — the same
 * seam Mental uses. On classic servers this delegates to the
 * {@code BukkitScheduler}; a future Folia implementation routes through the
 * region-aware schedulers without touching call sites. Boxer brains are
 * driven through {@code repeatOn(entity, …)} so each bot's work runs on the
 * thread that owns its entity by construction.
 */
public interface Scheduling {

    void runGlobal(@NotNull Runnable task);

    void runAt(@NotNull Location location, @NotNull Runnable task);

    /**
     * Runs on the thread that owns {@code entity}. If the entity is removed
     * before execution, {@code retired} runs instead (possibly immediately).
     */
    void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    void runAsync(@NotNull Runnable task);

    @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task);

    @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired);

    @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task);

    @NotNull String describe();
}

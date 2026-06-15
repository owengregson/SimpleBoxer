package me.vexmc.simpleboxer.common.scheduling;

import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * The one scheduling surface SimpleBoxer code is allowed to touch — the same
 * seam Mental uses. On classic servers this delegates to the
 * {@code BukkitScheduler}; on Folia the region-aware implementation routes
 * through the global/region/entity/async schedulers without touching call
 * sites. Boxer brains are driven through {@code repeatOn(entity, …)} so each
 * bot's work runs on the thread that owns its entity by construction, and
 * spawns run through {@code runAt(location, …)} so placement lands on the
 * region thread that owns the target chunk — the only thread on which Folia's
 * player-list bootstrap and {@code ServerLevel.getCurrentWorldData()} are
 * valid.
 */
public interface Scheduling {

    void runGlobal(@NotNull Runnable task);

    void runAt(@NotNull Location location, @NotNull Runnable task);

    /**
     * Whether the calling thread may mutate {@code location}'s region right
     * now — true on the classic main thread, true on Folia only when the
     * current region thread owns that location. Callers use this to run work
     * inline (and complete a blocking caller's future) instead of scheduling
     * a hop that would deadlock a same-thread waiter.
     */
    boolean ownsRegion(@NotNull Location location);

    /**
     * Runs on the thread that owns {@code entity}. If the entity is removed
     * before execution, {@code retired} runs instead (possibly immediately).
     */
    void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    /** {@link #runOn} after a tick delay. */
    void runLaterOn(@NotNull Entity entity, long delayTicks,
            @NotNull Runnable task, @NotNull Runnable retired);

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

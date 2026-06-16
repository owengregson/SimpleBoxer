package me.vexmc.simpleboxer.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Region-threaded scheduling for Folia. The {@code Scheduling} contract maps
 * onto Folia's four schedulers:
 *
 * <ul>
 *   <li>{@code runGlobal} → {@code GlobalRegionScheduler} (server-wide state)</li>
 *   <li>{@code runAt(location)} → {@code RegionScheduler} (the region owning a
 *       chunk — where spawn placement and world writes are legal)</li>
 *   <li>{@code runOn/runLaterOn/repeatOn(entity)} → the entity's
 *       {@code EntityScheduler} (work follows the entity across region
 *       hand-offs; its {@code retired} callback fires if the entity is
 *       removed)</li>
 *   <li>{@code runAsync/repeatAsync} → {@code AsyncScheduler}</li>
 * </ul>
 *
 * <p>SimpleBoxer compiles against the floor Paper API (1.17.1), which predates
 * the Folia scheduler types, so every call here is reflective — resolved once
 * against the <em>public</em> scheduler interfaces (never the impl classes, to
 * avoid access checks) and invoked on the live scheduler instances. This class
 * is only ever constructed when {@link FoliaSupport#isFolia()} is true.</p>
 */
public final class FoliaScheduling implements Scheduling {

    private static final String SCHEDULER_PACKAGE =
            "io.papermc.paper.threadedregions.scheduler.";

    private final Plugin plugin;
    private final Server server;

    private final Object globalScheduler;
    private final Object regionScheduler;
    private final Object asyncScheduler;

    private final Method globalExecute;           // (Plugin, Runnable) -> void
    private final Method globalRunAtFixedRate;    // (Plugin, Consumer, long, long) -> ScheduledTask
    private final Method regionExecute;           // (Plugin, Location, Runnable) -> void
    private final Method asyncRunNow;             // (Plugin, Consumer) -> ScheduledTask
    private final Method asyncRunAtFixedRate;     // (Plugin, Consumer, long, long, TimeUnit) -> ScheduledTask
    private final Method isOwnedByCurrentRegion;  // Server.(Location) -> boolean
    private final Method entityGetScheduler;      // Entity.() -> EntityScheduler
    private final Method entityRun;               // (Plugin, Consumer, Runnable) -> ScheduledTask
    private final Method entityRunDelayed;        // (Plugin, Consumer, Runnable, long) -> ScheduledTask
    private final Method entityRunAtFixedRate;    // (Plugin, Consumer, Runnable, long, long) -> ScheduledTask
    private final Method taskCancel;              // ScheduledTask.() -> CancelledState
    private final Method taskGetExecutionState;   // ScheduledTask.() -> ExecutionState

    /** A handle for an entity task that could not schedule (entity already retired). */
    private static final TaskHandle RETIRED_HANDLE = new TaskHandle() {
        @Override
        public void cancel() {}

        @Override
        public boolean cancelled() {
            return true;
        }
    };

    public FoliaScheduling(@NotNull Plugin plugin) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Bukkit.getServer();

        this.globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        this.regionScheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
        this.asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);

        Class<?> globalType = scheduler("GlobalRegionScheduler");
        Class<?> regionType = scheduler("RegionScheduler");
        Class<?> asyncType = scheduler("AsyncScheduler");
        Class<?> entityType = scheduler("EntityScheduler");
        Class<?> taskType = scheduler("ScheduledTask");

        this.globalExecute = globalType.getMethod("execute", Plugin.class, Runnable.class);
        this.globalRunAtFixedRate = globalType.getMethod(
                "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
        this.regionExecute = regionType.getMethod(
                "execute", Plugin.class, Location.class, Runnable.class);
        this.asyncRunNow = asyncType.getMethod("runNow", Plugin.class, Consumer.class);
        this.asyncRunAtFixedRate = asyncType.getMethod(
                "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
        this.entityRun = entityType.getMethod(
                "run", Plugin.class, Consumer.class, Runnable.class);
        this.entityRunDelayed = entityType.getMethod(
                "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
        this.entityRunAtFixedRate = entityType.getMethod(
                "runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
        this.taskCancel = taskType.getMethod("cancel");
        this.taskGetExecutionState = taskType.getMethod("getExecutionState");

        this.isOwnedByCurrentRegion = server.getClass().getMethod("isOwnedByCurrentRegion", Location.class);
        this.entityGetScheduler = Entity.class.getMethod("getScheduler");
    }

    private static Class<?> scheduler(String simpleName) throws ClassNotFoundException {
        return Class.forName(SCHEDULER_PACKAGE + simpleName);
    }

    /* ------------------------------------------------------------------ */
    /*  Global / region / ownership                                        */
    /* ------------------------------------------------------------------ */

    @Override
    public void runGlobal(@NotNull Runnable task) {
        invoke(globalExecute, globalScheduler, plugin, task);
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        invoke(regionExecute, regionScheduler, plugin, location, task);
    }

    @Override
    public boolean ownsRegion(@NotNull Location location) {
        Object owned = invoke(isOwnedByCurrentRegion, server, location);
        return owned instanceof Boolean value && value;
    }

    @Override
    public boolean autoTicksEntities() {
        // Every placed entity is ticked by its owning region; the brain must not
        // also tick the boxer, or its doTick would run twice per server tick.
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Entity-owned                                                       */
    /* ------------------------------------------------------------------ */

    @Override
    public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        Object scheduled = invoke(entityRun, schedulerOf(entity), plugin, consumer(task), retired);
        if (scheduled == null) {
            // The entity was already retired; honor the contract's "possibly
            // immediately" retirement so callers' cleanup still runs.
            retired.run();
        }
    }

    @Override
    public void runLaterOn(@NotNull Entity entity, long delayTicks,
            @NotNull Runnable task, @NotNull Runnable retired) {
        Object scheduled = invoke(entityRunDelayed, schedulerOf(entity),
                plugin, consumer(task), retired, atLeastOne(delayTicks));
        if (scheduled == null) {
            retired.run();
        }
    }

    @Override
    public @NotNull TaskHandle repeatOn(@NotNull Entity entity, long initialTicks, long periodTicks,
            @NotNull Runnable task, @NotNull Runnable retired) {
        Object scheduled = invoke(entityRunAtFixedRate, schedulerOf(entity),
                plugin, consumer(task), retired, atLeastOne(initialTicks), atLeastOne(periodTicks));
        if (scheduled == null) {
            retired.run();
            return RETIRED_HANDLE;
        }
        return new FoliaHandle(scheduled);
    }

    /* ------------------------------------------------------------------ */
    /*  Global repeat / async                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
        Object scheduled = invoke(globalRunAtFixedRate, globalScheduler,
                plugin, consumer(task), atLeastOne(initialTicks), atLeastOne(periodTicks));
        return new FoliaHandle(scheduled);
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        invoke(asyncRunNow, asyncScheduler, plugin, consumer(task));
    }

    @Override
    public @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period,
            @NotNull Runnable task) {
        long initialMillis = atLeastOne(initial.toMillis());
        long periodMillis = atLeastOne(period.toMillis());
        Object scheduled = invoke(asyncRunAtFixedRate, asyncScheduler,
                plugin, consumer(task), initialMillis, periodMillis, TimeUnit.MILLISECONDS);
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull String describe() {
        return "folia";
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection plumbing                                                */
    /* ------------------------------------------------------------------ */

    private Object schedulerOf(Entity entity) {
        return invoke(entityGetScheduler, entity);
    }

    /** Wrap a Runnable as the {@code Consumer<ScheduledTask>} the API expects. */
    private static Consumer<Object> consumer(Runnable task) {
        return ignoredTask -> task.run();
    }

    /** Folia rejects sub-1 delays/periods; the boxer's 1-tick loop already sits at the floor. */
    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException("Folia scheduler method inaccessible: " + method, inaccessible);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Folia scheduler call failed: " + method, cause);
        }
    }

    private final class FoliaHandle implements TaskHandle {

        private final Object scheduledTask;

        private FoliaHandle(Object scheduledTask) {
            this.scheduledTask = scheduledTask;
        }

        @Override
        public void cancel() {
            invoke(taskCancel, scheduledTask);
        }

        @Override
        public boolean cancelled() {
            Object state = invoke(taskGetExecutionState, scheduledTask);
            // ExecutionState constants are CANCELLED and CANCELLED_RUNNING.
            return state != null && state.toString().contains("CANCELLED");
        }
    }
}

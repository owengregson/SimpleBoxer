package me.vexmc.simpleboxer.boxer;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Feeds a boxer the knockback the server applies to it, captured the instant the
 * server computes it from Paper's {@code EntityKnockbackEvent} — the full,
 * pre-{@code doTick} velocity a real client predicts.
 *
 * <p>The alternative (polling the entity's {@code hurtMarked} flag in the brain
 * tick) races the server's own entity tick on Folia and usually loses: by the
 * time the brain reads the motion field, one tick of ground friction has eaten
 * the horizontal component, so the boxer "pops up" with no push. This event
 * fires synchronously when the knockback is applied, so it always sees the full
 * value, whichever order the region runs things in.</p>
 *
 * <p>The event ({@code 1.20.6+}) postdates the floor API this plugin compiles
 * against, so it is loaded and registered reflectively; it is only wired up
 * where it is both present and needed — see {@link BoxerManager}. The event's
 * {@code getKnockback()} is the velocity <em>delta</em> the server adds, so the
 * boxer's resulting velocity is {@code entity.getVelocity() + getKnockback()}.</p>
 */
public final class KnockbackListener implements Listener {

    private static final String EVENT_CLASS = "io.papermc.paper.event.entity.EntityKnockbackEvent";

    private final BoxerManager manager;
    private final Logger logger;

    public KnockbackListener(@NotNull BoxerManager manager, @NotNull Logger logger) {
        this.manager = manager;
        this.logger = logger;
    }

    /** Whether Paper's EntityKnockbackEvent exists on this server. */
    public static boolean eventAvailable() {
        try {
            Class.forName(EVENT_CLASS);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /** Register the reflective handler. The caller ensures {@link #eventAvailable()}. */
    public void register(@NotNull Plugin plugin) {
        try {
            Class<?> eventClass = Class.forName(EVENT_CLASS);
            Method getEntity = eventClass.getMethod("getEntity");
            Method getKnockback = eventClass.getMethod("getKnockback");
            Method getCause = eventClass.getMethod("getCause");
            EventExecutor executor = (listener, event) -> handle(event, getEntity, getKnockback, getCause);
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            // MONITOR + ignoreCancelled: read the final value the server will
            // apply, and only for knockback that is actually applied.
            Bukkit.getPluginManager().registerEvent(typed, this, EventPriority.MONITOR, executor, plugin, true);
        } catch (ReflectiveOperationException failure) {
            logger.warning("EntityKnockbackEvent integration failed; boxer knockback "
                    + "falls back to the polling path: " + failure);
        }
    }

    private void handle(@NotNull Event event, @NotNull Method getEntity,
            @NotNull Method getKnockback, @NotNull Method getCause) {
        try {
            if (!(getEntity.invoke(event) instanceof Player player)) {
                return;
            }
            // Explosions ride the dedicated ClientboundExplodePacket path (which
            // ADDS to motion); everything else REPLACES, the way a velocity
            // packet does. Skip them here to avoid applying the same shove twice.
            Object cause = getCause.invoke(event);
            if (cause != null && cause.toString().contains("EXPLOSION")) {
                return;
            }
            manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
                Vector knockback = knockbackOf(getKnockback, event);
                if (knockback == null) {
                    return;
                }
                Vector current = player.getVelocity();
                boxer.onKnockback(current.getX() + knockback.getX(),
                        current.getY() + knockback.getY(),
                        current.getZ() + knockback.getZ());
            });
        } catch (Throwable ignored) {
            // A missed knockback read is best-effort; the boxer simply takes none.
        }
    }

    private static @Nullable Vector knockbackOf(@NotNull Method getKnockback, @NotNull Event event) {
        try {
            return (Vector) getKnockback.invoke(event);
        } catch (Throwable failure) {
            return null;
        }
    }
}

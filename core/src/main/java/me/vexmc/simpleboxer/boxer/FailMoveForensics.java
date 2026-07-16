package me.vexmc.simpleboxer.boxer;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Debug forensics for the server's movement anti-cheat: logs every
 * {@code PlayerFailMoveEvent} (modern Paper) with the gate that fired and both
 * positions. {@code MOVED_TOO_QUICKLY}/{@code MOVED_WRONGLY} warn on their own,
 * but {@code CLIPPED_INTO_BLOCK} (Paper's hasNewCollision) and event-cancelled
 * moves reject SILENTLY — a silent rejection stream is exactly how the 0.6.x
 * "wall glue" hid. A cancelled {@code PlayerJumpEvent} also teleports the mover
 * back (plugin-inducible) and surfaces here with its own reason.
 *
 * <p>Debug-gated ({@code -Dsimpleboxer.debug=true}) and reflective: the event
 * postdates the floor API this plugin compiles against, so where the class is
 * absent registration is skipped. Every player's failures log, not only
 * boxers' — a real client rubber-banding beside a healthy boxer is itself a
 * finding.</p>
 */
final class FailMoveForensics implements Listener {

    private static final String EVENT_CLASS = "io.papermc.paper.event.player.PlayerFailMoveEvent";
    /** -Dsimpleboxer.debug=true: same flag that gates BoxerImpl's traces. */
    private static final boolean DEBUG = Boolean.getBoolean("simpleboxer.debug");

    private final Logger logger;

    private FailMoveForensics(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Register the reflective handler when debugging is armed and the event
     * exists on this version; a no-op everywhere else. Called once from the
     * {@link BoxerManager} constructor.
     */
    static void register(@NotNull Plugin plugin) {
        if (!DEBUG) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(EVENT_CLASS);
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getFailReason = eventClass.getMethod("getFailReason");
            Method getFrom = eventClass.getMethod("getFrom");
            Method getTo = eventClass.getMethod("getTo");
            FailMoveForensics forensics = new FailMoveForensics(plugin.getLogger());
            EventExecutor executor = (listener, event) ->
                    forensics.handle(event, getPlayer, getFailReason, getFrom, getTo);
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            // MONITOR: observe the verdict other plugins may already have altered.
            Bukkit.getPluginManager().registerEvent(typed, forensics, EventPriority.MONITOR,
                    executor, plugin, false);
            plugin.getLogger().info("[debug] PlayerFailMoveEvent forensics armed");
        } catch (ClassNotFoundException preFailMoveApi) {
            plugin.getLogger().info("[debug] PlayerFailMoveEvent absent on this version — "
                    + "fail-move forensics disabled");
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("PlayerFailMoveEvent forensics failed to arm: " + failure);
        }
    }

    private void handle(@NotNull Event event, @NotNull Method getPlayer,
            @NotNull Method getFailReason, @NotNull Method getFrom, @NotNull Method getTo) {
        try {
            Player player = (Player) getPlayer.invoke(event);
            Object reason = getFailReason.invoke(event);
            Location from = (Location) getFrom.invoke(event);
            Location to = (Location) getTo.invoke(event);
            // %.5f: rejection deltas can sit far below the wallCollide trace's
            // %.3f grid (the glue band was 1e-8 deep).
            logger.info(String.format(
                    "[debug failmove] %s %s from=(%.5f,%.5f,%.5f) to=(%.5f,%.5f,%.5f)",
                    player.getName(), reason,
                    from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ()));
        } catch (Throwable ignored) {
            // Forensics never break the move pipeline.
        }
    }
}

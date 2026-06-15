package me.vexmc.simpleboxer.nms;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional coexistence with PacketEvents — the packet library most combat and
 * anti-cheat plugins (the very things a boxer exists to spar against) build on.
 *
 * <p>A boxer rides a clientless {@code EmbeddedChannel}. PacketEvents injects
 * into every joining player and, on {@code PlayerJoinEvent}, kicks anyone whose
 * channel it can't resolve with <em>"PacketEvents failed to inject into a
 * channel"</em>. It already <b>skips</b> channels it recognises as fake (its
 * {@code FakeChannelUtil} matches {@code EmbeddedChannel}) — but only once it
 * has the channel. PacketEvents finds the channel by first consulting its own
 * {@code UUID -> channel} map and only then walking
 * {@code player -> ServerPlayer -> connection -> channel} by reflection. That
 * walk returns null for a boxer on Folia (where {@code CraftEntity.getHandle()}
 * is region-thread guarded), so the fake-channel skip never runs and the boxer
 * is kicked a tick after it joins.</p>
 *
 * <p>We close the gap the same way PacketEvents caches real players: by seeding
 * its map with the boxer's channel <b>before</b> the join fires. PacketEvents
 * then resolves the channel by uuid, sees it is fake, and leaves the boxer
 * alone.</p>
 *
 * <p>PacketEvents is frequently <b>shaded and relocated</b> inside another
 * plugin, so its package can't be named at compile time. We locate it at
 * runtime through its own Bukkit listener registered on {@code PlayerJoinEvent}
 * (always under {@code …packetevents.bukkit.Internal*Listener}), derive the
 * relocation prefix from that listener's class name, and load the PacketEvents
 * API from the listener's own classloader. This works for a standalone
 * PacketEvents and for any shaded/relocated copy alike; absent PacketEvents,
 * every method is a silent no-op.</p>
 */
final class PacketEventsCompat {

    private final Logger logger;

    private volatile boolean resolved;
    private volatile @Nullable Object protocolManager;
    private volatile @Nullable Method setChannelMethod;
    private volatile @Nullable Method removeChannelMethod;

    PacketEventsCompat(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Register the boxer's fake channel so PacketEvents recognises and skips it.
     * Must run before the join event (i.e. before {@code placeNewPlayer}).
     */
    void markFakeChannel(@NotNull UUID uuid, @NotNull Object channel) {
        if (!resolve()) {
            return;
        }
        try {
            setChannelMethod.invoke(protocolManager, uuid, channel);
        } catch (Throwable failure) {
            logger.fine("PacketEvents fake-channel registration failed for " + uuid + ": " + failure);
        }
    }

    /** Drop the boxer's channel from PacketEvents' map on despawn. */
    void forgetFakeChannel(@NotNull UUID uuid) {
        if (!resolve()) {
            return;
        }
        try {
            removeChannelMethod.invoke(protocolManager, uuid);
        } catch (Throwable failure) {
            logger.fine("PacketEvents fake-channel cleanup failed for " + uuid + ": " + failure);
        }
    }

    /** Resolve PacketEvents lazily (at first spawn, when it is enabled); cached once found. */
    private boolean resolve() {
        if (resolved) {
            return true;
        }
        synchronized (this) {
            if (resolved) {
                return true;
            }
            try {
                Class<?> packetEventsClass = findPacketEventsClass();
                if (packetEventsClass == null) {
                    return false; // PacketEvents not installed — stay a no-op
                }
                Object api = packetEventsClass.getMethod("getAPI").invoke(null);
                if (api == null) {
                    return false; // present but not built yet — retry on the next spawn
                }
                Object pm = api.getClass().getMethod("getProtocolManager").invoke(api);
                if (pm == null) {
                    return false;
                }
                this.setChannelMethod = pm.getClass().getMethod("setChannel", UUID.class, Object.class);
                this.removeChannelMethod = pm.getClass().getMethod("removeChannelById", UUID.class);
                this.protocolManager = pm;
                this.resolved = true;
                logger.info("PacketEvents detected (" + packetEventsClass.getName()
                        + ") — boxers register as fake channels to avoid injection kicks.");
                return true;
            } catch (Throwable unavailable) {
                logger.fine("PacketEvents integration unavailable: " + unavailable);
                return false;
            }
        }
    }

    /**
     * Locate the (possibly relocated/shaded) PacketEvents API class via one of
     * its Bukkit listeners on {@code PlayerJoinEvent}. The listener lives at
     * {@code <root>.bukkit.Internal*Listener}; the API class is
     * {@code <root>.PacketEvents} after mapping the impl root
     * ({@code io.github.retrooper.packetevents}) to the API root
     * ({@code com.github.retrooper.packetevents}) — a no-op when a shade merged
     * both roots into one package.
     */
    private @Nullable Class<?> findPacketEventsClass() {
        for (RegisteredListener registered : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
            Class<?> listenerType = registered.getListener().getClass();
            String name = listenerType.getName();
            int bukkit = name.indexOf(".bukkit.");
            if (bukkit < 0) {
                continue;
            }
            // PacketEvents' Bukkit listeners are <root>.bukkit.Internal*Listener.
            // Match the package marker, or the distinctive class name for shades
            // that relocate to a package without "packetevents" in it.
            String simpleName = listenerType.getSimpleName();
            boolean packetEventsListener = name.contains("packetevents")
                    || (simpleName.startsWith("Internal") && simpleName.endsWith("Listener"));
            if (!packetEventsListener) {
                continue;
            }
            String implRoot = name.substring(0, bukkit);
            ClassLoader owner = listenerType.getClassLoader();
            for (String candidate : new String[] {
                    implRoot.replace("io.github.retrooper", "com.github.retrooper") + ".PacketEvents",
                    implRoot + ".PacketEvents"}) {
                try {
                    return Class.forName(candidate, false, owner);
                } catch (Throwable wrongCandidate) {
                    // Try the next derivation / next listener.
                }
            }
        }
        // Fallback: a standalone, non-relocated copy on our own classloader.
        try {
            return Class.forName("com.github.retrooper.packetevents.PacketEvents");
        } catch (Throwable absent) {
            return null;
        }
    }
}

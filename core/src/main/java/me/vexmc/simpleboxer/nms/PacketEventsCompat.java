package me.vexmc.simpleboxer.nms;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
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
 * {@code UUID -> channel} map ({@code ProtocolManager.CHANNELS}) and only then
 * walking {@code player -> ServerPlayer -> connection -> channel} by reflection.
 * That walk returns null for a boxer on Folia (where
 * {@code CraftEntity.getHandle()} is region-thread guarded), so the fake-channel
 * skip never runs and the boxer is kicked a tick after it joins.</p>
 *
 * <p>We close the gap the same way PacketEvents caches real players: by seeding
 * its map with the boxer's channel <b>before</b> the join fires. PacketEvents
 * then resolves the channel by uuid, sees it is fake, and leaves the boxer
 * alone.</p>
 *
 * <p>PacketEvents may be a standalone plugin or shaded+relocated inside another,
 * so its package can't be named at compile time. We find it at runtime — first
 * via the standalone {@code packetevents} plugin, then via its own Bukkit
 * listener on {@code PlayerJoinEvent} — and load the API from the owning
 * classloader. Everything is reflective and best-effort; absent PacketEvents,
 * every method is a no-op. The one-time detection result is logged so the
 * integration can be confirmed in-server.</p>
 */
final class PacketEventsCompat {

    private static final String API_CLASS = "com.github.retrooper.packetevents.PacketEvents";

    private final Logger logger;

    private volatile boolean resolveAttempted;
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
        resolveOnce();
        Object pm = protocolManager;
        Method seed = setChannelMethod;
        if (pm == null || seed == null) {
            return;
        }
        try {
            seed.invoke(pm, uuid, channel);
        } catch (Throwable failure) {
            logger.warning("PacketEvents fake-channel registration failed for " + uuid + ": " + failure);
        }
    }

    /** Drop the boxer's channel from PacketEvents' map on despawn. */
    void forgetFakeChannel(@NotNull UUID uuid) {
        Object pm = protocolManager;
        Method remove = removeChannelMethod;
        if (pm == null || remove == null) {
            return;
        }
        try {
            remove.invoke(pm, uuid);
        } catch (Throwable ignored) {
            // Cleanup is cosmetic; a stale map entry under a random uuid is harmless.
        }
    }

    /** Resolve PacketEvents once (at the first spawn, when it is enabled), logging the outcome. */
    private void resolveOnce() {
        if (resolveAttempted) {
            return;
        }
        synchronized (this) {
            if (resolveAttempted) {
                return;
            }
            resolveAttempted = true;
            try {
                Class<?> packetEventsClass = findPacketEventsClass();
                if (packetEventsClass == null) {
                    logger.info("PacketEvents not detected — boxers will not pre-register a fake channel. "
                            + "PlayerJoinEvent listeners present: " + joinListenerNames());
                    return;
                }
                Object api = packetEventsClass.getMethod("getAPI").invoke(null);
                if (api == null) {
                    logger.warning("PacketEvents found (" + packetEventsClass.getName()
                            + ") but getAPI() is null — integration disabled this session.");
                    return;
                }
                Object pm = api.getClass().getMethod("getProtocolManager").invoke(api);
                Method setChannel = findMethod(pm.getClass(), "setChannel", 2);
                Method removeChannel = findMethod(pm.getClass(), "removeChannelById", 1);
                this.protocolManager = pm;
                this.setChannelMethod = setChannel;
                this.removeChannelMethod = removeChannel;
                logger.info("PacketEvents detected (" + packetEventsClass.getName() + "); protocolManager="
                        + pm.getClass().getName() + ", setChannel=" + (setChannel != null)
                        + ", removeChannelById=" + (removeChannel != null)
                        + " — boxers register as fake channels to avoid injection kicks.");
                if (setChannel == null) {
                    logger.warning("PacketEvents setChannel(UUID, channel) not found on "
                            + pm.getClass().getName() + " — boxers may still be kicked.");
                }
            } catch (Throwable failure) {
                logger.warning("PacketEvents integration failed to initialise: " + failure);
            }
        }
    }

    /** Locate the (standalone or shaded/relocated) PacketEvents API class. */
    private @Nullable Class<?> findPacketEventsClass() {
        // 1. Standalone PacketEvents plugin (plugin.yml name: "packetevents").
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getName().equalsIgnoreCase("packetevents")) {
                Class<?> loaded = tryLoad(plugin.getClass().getClassLoader(), API_CLASS);
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        // 2. Shaded/relocated: derive the package from a PacketEvents Bukkit
        //    listener on PlayerJoinEvent (always <root>.bukkit.Internal*Listener)
        //    and load the API from its classloader.
        for (RegisteredListener registered : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
            Class<?> type = registered.getListener().getClass();
            String name = type.getName();
            int bukkit = name.indexOf(".bukkit.");
            if (bukkit < 0) {
                continue;
            }
            String simpleName = type.getSimpleName();
            boolean looksLikePacketEvents = name.contains("packetevents")
                    || (simpleName.startsWith("Internal") && simpleName.endsWith("Listener"));
            if (!looksLikePacketEvents) {
                continue;
            }
            String implRoot = name.substring(0, bukkit);
            ClassLoader owner = type.getClassLoader();
            for (String candidate : new String[] {
                    implRoot.replace("io.github.retrooper", "com.github.retrooper") + ".PacketEvents",
                    implRoot + ".PacketEvents"}) {
                Class<?> loaded = tryLoad(owner, candidate);
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        // 3. Our own classloader (PacketEvents shaded into us — never is, but cheap).
        return tryLoad(getClass().getClassLoader(), API_CLASS);
    }

    private String joinListenerNames() {
        List<String> names = new ArrayList<>();
        try {
            for (RegisteredListener registered : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
                names.add(registered.getListener().getClass().getName()
                        + " [" + registered.getPlugin().getName() + "]");
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
        return names.toString();
    }

    private static @Nullable Class<?> tryLoad(@Nullable ClassLoader loader, @NotNull String name) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static @Nullable Method findMethod(@NotNull Class<?> owner, @NotNull String name, int paramCount) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }
}

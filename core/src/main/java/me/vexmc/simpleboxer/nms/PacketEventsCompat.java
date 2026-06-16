package me.vexmc.simpleboxer.nms;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional coexistence with PacketEvents — the packet library most combat and
 * anti-cheat plugins (the very things a boxer exists to spar against) build on.
 *
 * <p>A boxer rides a clientless channel ({@link FakeChannel}). On join,
 * PacketEvents resolves the player's channel and kicks anyone it can't account
 * for with <em>"PacketEvents failed to inject into a channel"</em>. Its decision
 * (in {@code InternalBukkitListener.onPostJoin} and the Paper/login variants) is:
 * if {@code getUser(player)} is null and {@code getChannel(player)} is not a
 * recognised fake channel, kick. We seed the boxer's channel into PacketEvents'
 * {@code UUID -> channel} map before the join so {@code getChannel} resolves it,
 * and {@link FakeChannel} carries a name every version treats as fake.</p>
 *
 * <p>{@code CHANNELS} is a {@code static} field on the {@code ProtocolManager}
 * interface — one map per loaded copy of PacketEvents (a standalone plugin plus
 * any shaded+relocated copies). We discover and seed them all.</p>
 *
 * <p>{@link #diagnose} is a temporary instrument: it logs, per copy, the exact
 * inputs to the kick decision for an already-spawned boxer, so a stubborn kick
 * can be traced to the precise assumption that fails rather than guessed at.</p>
 */
final class PacketEventsCompat {

    private static final String API_CLASS = "com.github.retrooper.packetevents.PacketEvents";
    private static final String FAKE_CHANNEL_UTIL = "com.github.retrooper.packetevents.util.FakeChannelUtil";

    private final Logger logger;

    private volatile boolean resolveAttempted;
    private volatile boolean firstSeedLogged;
    private volatile boolean diagnosedOnce;
    private volatile List<Target> targets = List.of();

    PacketEventsCompat(@NotNull Logger logger) {
        this.logger = logger;
    }

    /** One resolved PacketEvents copy: its API, protocol manager and the channel-map methods. */
    private static final class Target {
        final String source;
        final Object api;
        final ClassLoader classLoader;
        final Object protocolManager;
        final Method setChannel;
        final @Nullable Method getChannelByUuid;
        final @Nullable Method removeChannel;

        Target(String source, Object api, ClassLoader classLoader, Object protocolManager,
               Method setChannel, @Nullable Method getChannelByUuid, @Nullable Method removeChannel) {
            this.source = source;
            this.api = api;
            this.classLoader = classLoader;
            this.protocolManager = protocolManager;
            this.setChannel = setChannel;
            this.getChannelByUuid = getChannelByUuid;
            this.removeChannel = removeChannel;
        }
    }

    /**
     * Register the boxer's fake channel with every PacketEvents copy so each
     * recognises and skips it. Must run before the join event (before
     * {@code placeNewPlayer}).
     */
    void markFakeChannel(@NotNull UUID uuid, @NotNull Object channel) {
        resolveOnce();
        List<Target> all = targets;
        if (all.isEmpty()) {
            return;
        }
        int seeded = 0;
        int confirmed = 0;
        for (Target target : all) {
            try {
                target.setChannel.invoke(target.protocolManager, uuid, channel);
                seeded++;
                if (target.getChannelByUuid != null) {
                    Object readBack = target.getChannelByUuid.invoke(target.protocolManager, uuid);
                    if (readBack == channel) {
                        confirmed++;
                    } else {
                        logger.warning("PacketEvents[" + target.source + "] channel read-back mismatch for "
                                + uuid + " — getChannel(uuid) returned "
                                + describe(readBack) + "; this copy may still kick the boxer.");
                    }
                }
            } catch (Throwable failure) {
                logger.warning("PacketEvents[" + target.source + "] fake-channel registration failed for "
                        + uuid + ": " + failure);
            }
        }
        if (!firstSeedLogged) {
            firstSeedLogged = true;
            logger.info("PacketEvents fake-channel seeding: " + seeded + "/" + all.size()
                    + " copy(ies) seeded, " + confirmed + " confirmed by read-back (keyed by uuid " + uuid + ").");
        }
    }

    /**
     * Log the exact inputs to PacketEvents' join-time kick decision for an
     * already-placed boxer, per copy: the channel its {@code PlayerManager}
     * resolves <em>from the Player</em> (the path the kicking listener uses),
     * whether that channel is considered fake, whether a User exists, and the
     * seeded-vs-actual uuid. Runs only for the first boxer to keep logs quiet.
     */
    void diagnose(@NotNull Player player, @NotNull UUID seededUuid) {
        if (diagnosedOnce) {
            return;
        }
        List<Target> all = targets;
        if (all.isEmpty()) {
            return;
        }
        diagnosedOnce = true;
        UUID playerUuid = player.getUniqueId();
        logger.info("PacketEvents kick-decision diagnosis for boxer " + player.getName()
                + " — seeded uuid=" + seededUuid + ", player.getUniqueId()=" + playerUuid
                + (seededUuid.equals(playerUuid) ? " (match)" : " (MISMATCH — seed is keyed wrong!)"));
        for (Target target : all) {
            try {
                Object playerManager = invoke0(target.api, "getPlayerManager");
                Object channelViaPlayer = playerManager == null ? null
                        : invoke1(playerManager, "getChannel", Object.class, player);
                Object channelViaUuid = target.getChannelByUuid == null ? null
                        : target.getChannelByUuid.invoke(target.protocolManager, playerUuid);
                Object user = playerManager == null ? null
                        : invoke1(playerManager, "getUser", Object.class, player);
                Boolean fake = isFakeChannel(target.classLoader, channelViaPlayer);
                logger.info("PacketEvents[" + target.source + "] version=" + apiVersion(target.api)
                        + " | getChannel(player)=" + describe(channelViaPlayer)
                        + " | getChannel(uuid)=" + describe(channelViaUuid)
                        + " | isFakeChannel(getChannel(player))=" + (fake == null ? "<error>" : fake)
                        + " | getUser(player)=" + (user == null ? "null" : "present")
                        + "  => would " + verdict(channelViaPlayer, fake, user));
            } catch (Throwable failure) {
                logger.warning("PacketEvents[" + target.source + "] diagnosis failed: " + failure);
            }
        }
        logger.info("PacketEvents join/login listeners present: " + allJoinLoginListenerNames());
    }

    /** Drop the boxer's channel from every PacketEvents map on despawn. */
    void forgetFakeChannel(@NotNull UUID uuid) {
        for (Target target : targets) {
            if (target.removeChannel == null) {
                continue;
            }
            try {
                target.removeChannel.invoke(target.protocolManager, uuid);
            } catch (Throwable ignored) {
                // Cleanup is cosmetic; a stale map entry under a random uuid is harmless.
            }
        }
    }

    /** Resolve every PacketEvents copy once (at the first spawn), logging the outcome. */
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
                Set<Class<?>> classes = discoverPacketEventsClasses();
                if (classes.isEmpty()) {
                    logger.info("PacketEvents not detected — boxers will not pre-register a fake channel. "
                            + "PlayerJoinEvent listeners present: " + allJoinLoginListenerNames());
                    return;
                }
                List<Target> built = new ArrayList<>();
                Set<Object> seenManagers = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Class<?> packetEventsClass : classes) {
                    Target target = resolveTarget(packetEventsClass, seenManagers);
                    if (target != null) {
                        built.add(target);
                    }
                }
                targets = List.copyOf(built);
                if (built.isEmpty()) {
                    logger.warning("PacketEvents present but no copy exposed a usable protocol manager — "
                            + "boxers may be kicked.");
                    return;
                }
                StringBuilder summary = new StringBuilder();
                for (Target target : built) {
                    summary.append("\n  - ").append(target.protocolManager.getClass().getName())
                            .append(" (via ").append(target.source)
                            .append(", version=").append(apiVersion(target.api))
                            .append(", readBack=").append(target.getChannelByUuid != null).append(")");
                }
                logger.info("PacketEvents detected: " + built.size()
                        + " copy(ies) will register boxers as fake channels to avoid injection kicks:" + summary);
            } catch (Throwable failure) {
                logger.warning("PacketEvents integration failed to initialise: " + failure);
            }
        }
    }

    /** Build a {@link Target} for one PacketEvents copy, or null if it is unusable / a duplicate. */
    private @Nullable Target resolveTarget(@NotNull Class<?> packetEventsClass, @NotNull Set<Object> seenManagers) {
        try {
            Object api = packetEventsClass.getMethod("getAPI").invoke(null);
            if (api == null) {
                logger.warning("PacketEvents copy " + packetEventsClass.getName()
                        + " getAPI() is null — skipped this session.");
                return null;
            }
            // getAPI() hands back a non-public anonymous implementation
            // (e.g. SpigotPacketEventsBuilder$1); methods must be resolved on a
            // public supertype or invocation throws IllegalAccessException.
            Method getProtocolManager = accessibleMethod(api.getClass(), "getProtocolManager", 0);
            if (getProtocolManager == null) {
                logger.warning("PacketEvents copy " + packetEventsClass.getName()
                        + " has no resolvable getProtocolManager() — skipped.");
                return null;
            }
            Object pm = getProtocolManager.invoke(api);
            if (pm == null || !seenManagers.add(pm)) {
                return null; // unusable or a copy we already seeded (shared singleton)
            }
            Method setChannel = accessibleMethod(pm.getClass(), "setChannel", 2);
            if (setChannel == null) {
                logger.warning("PacketEvents copy " + packetEventsClass.getName()
                        + " has no setChannel(UUID, channel) on " + pm.getClass().getName()
                        + " — cannot seed; boxers may be kicked.");
                return null;
            }
            Method getChannel = accessibleMethod(pm.getClass(), "getChannel", 1);
            Method removeChannel = accessibleMethod(pm.getClass(), "removeChannelById", 1);
            return new Target(packetEventsClass.getName(), api, api.getClass().getClassLoader(),
                    pm, setChannel, getChannel, removeChannel);
        } catch (Throwable failure) {
            logger.warning("PacketEvents copy " + packetEventsClass.getName()
                    + " failed to initialise: " + failure);
            return null;
        }
    }

    /**
     * Find every loaded PacketEvents API class: the standalone {@code packetevents}
     * plugin, plus the copy behind every PacketEvents join/login listener (covers
     * shaded+relocated copies inside Grim and other anti-cheats), plus our own loader.
     */
    private @NotNull Set<Class<?>> discoverPacketEventsClasses() {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getName().equalsIgnoreCase("packetevents")) {
                addIfLoadable(found, plugin.getClass().getClassLoader(), API_CLASS);
            }
        }
        for (RegisteredListener registered : packetEventsListeners()) {
            Class<?> type = registered.getListener().getClass();
            ClassLoader owner = type.getClassLoader();
            addIfLoadable(found, owner, API_CLASS);
            for (String candidate : candidateApiClasses(type.getName())) {
                addIfLoadable(found, owner, candidate);
            }
        }
        addIfLoadable(found, getClass().getClassLoader(), API_CLASS);
        return found;
    }

    /**
     * Candidate fully-qualified {@code PacketEvents} class names for a relocated
     * copy, derived from one of its Bukkit listeners. A listener lives at
     * {@code <implRoot>.bukkit.Internal*Listener}, where {@code <implRoot>} is the
     * relocated {@code io.github.retrooper.packetevents}; the API class lives at
     * the relocated {@code com.github.retrooper.packetevents}. Different shaders
     * map those two source roots differently, so we try every plausible api root:
     * <ul>
     *   <li>prefix relocation keeping the io/com split (standalone, Grim):
     *       {@code io.github.retrooper -> com.github.retrooper};</li>
     *   <li>split-leaf relocation (e.g. Mental: {@code ....packetevents.impl} for
     *       impl, {@code ....packetevents}/{@code ....packetevents.api} for api);</li>
     *   <li>shared root (impl and api under the same relocated package).</li>
     * </ul>
     * Wrong guesses simply fail to load or fail {@code getAPI()} and are dropped.
     */
    private static @NotNull List<String> candidateApiClasses(@NotNull String listenerClassName) {
        List<String> candidates = new ArrayList<>();
        int bukkit = listenerClassName.indexOf(".bukkit.");
        if (bukkit < 0) {
            return candidates;
        }
        String implRoot = listenerClassName.substring(0, bukkit); // relocated io.github.retrooper.packetevents
        Set<String> roots = new LinkedHashSet<>();
        roots.add(implRoot.replace("io.github.retrooper", "com.github.retrooper"));
        roots.add(implRoot);
        int lastDot = implRoot.lastIndexOf('.');
        if (lastDot > 0) {
            String base = implRoot.substring(0, lastDot); // parent of the impl leaf
            roots.add(base);
            roots.add(base + ".api");
        }
        for (String root : roots) {
            candidates.add(root + ".PacketEvents");
        }
        return candidates;
    }

    private @NotNull List<RegisteredListener> packetEventsListeners() {
        List<RegisteredListener> out = new ArrayList<>();
        collectPacketEventsListeners(out, PlayerJoinEvent.getHandlerList());
        collectPacketEventsListeners(out, PlayerLoginEvent.getHandlerList());
        return out;
    }

    private void collectPacketEventsListeners(@NotNull List<RegisteredListener> out, @NotNull HandlerList handlers) {
        for (RegisteredListener registered : handlers.getRegisteredListeners()) {
            if (registered.getListener().getClass().getName().contains("packetevents")) {
                out.add(registered);
            }
        }
    }

    private void addIfLoadable(@NotNull Set<Class<?>> set, @Nullable ClassLoader loader, @NotNull String name) {
        Class<?> loaded = tryLoad(loader, name);
        if (loaded != null) {
            set.add(loaded);
        }
    }

    /** All join + login listeners with their owning plugin — diagnostic only. */
    private String allJoinLoginListenerNames() {
        List<String> names = new ArrayList<>();
        try {
            for (HandlerList handlers : new HandlerList[] {
                    PlayerJoinEvent.getHandlerList(), PlayerLoginEvent.getHandlerList()}) {
                for (RegisteredListener registered : handlers.getRegisteredListeners()) {
                    names.add(registered.getListener().getClass().getName()
                            + " [" + registered.getPlugin().getName() + "]");
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
        return names.toString();
    }

    private @Nullable Boolean isFakeChannel(@NotNull ClassLoader loader, @Nullable Object channel) {
        if (channel == null) {
            return false;
        }
        try {
            Class<?> util = Class.forName(FAKE_CHANNEL_UTIL, false, loader);
            Method method = accessibleMethod(util, "isFakeChannel", 1);
            if (method == null) {
                return null;
            }
            return (Boolean) method.invoke(null, channel);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String verdict(@Nullable Object channel, @Nullable Boolean fake, @Nullable Object user) {
        if (user != null) {
            return "NOT kick (user present)";
        }
        if (channel != null && Boolean.TRUE.equals(fake)) {
            return "NOT kick (fake channel)";
        }
        return "KICK";
    }

    private static String describe(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String apiVersion(@NotNull Object api) {
        try {
            Object version = invoke0(api, "getVersion");
            return version == null ? "?" : String.valueOf(version);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static @Nullable Object invoke0(@NotNull Object target, @NotNull String name) throws Exception {
        Method method = accessibleMethod(target.getClass(), name, 0);
        return method == null ? null : method.invoke(target);
    }

    private static @Nullable Object invoke1(@NotNull Object target, @NotNull String name,
                                            @NotNull Class<?> paramType, @NotNull Object arg) throws Exception {
        Method method = accessibleMethod(target.getClass(), name, 1);
        return method == null ? null : method.invoke(target, arg);
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

    /**
     * Resolve a public method by name + arity on the first <em>public</em> type in
     * {@code runtimeType}'s hierarchy that declares it. PacketEvents returns
     * instances of non-public anonymous classes; a method pulled straight off such
     * a class is public yet uninvokable ({@code IllegalAccessException}), so we look
     * it up on the public interface (or superclass) that actually declares it and
     * only fall back to {@code setAccessible} when no public declarer exists.
     */
    private static @Nullable Method accessibleMethod(@NotNull Class<?> runtimeType,
                                                     @NotNull String name, int arity) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(runtimeType);
        Method fallback = null;
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (type == null || !seen.add(type)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != arity
                        || method.isBridge()
                        || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                if (Modifier.isPublic(type.getModifiers())) {
                    return method; // declared on a public type → directly invokable
                }
                fallback = method; // public method on a non-public type → needs setAccessible
            }
            if (type.getSuperclass() != null) {
                queue.add(type.getSuperclass());
            }
            for (Class<?> iface : type.getInterfaces()) {
                queue.add(iface);
            }
        }
        if (fallback != null) {
            try {
                fallback.setAccessible(true);
            } catch (Throwable notPermitted) {
                // Best effort; invocation may still fail and is reported by the caller.
            }
        }
        return fallback;
    }
}

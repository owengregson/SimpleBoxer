package me.vexmc.simpleboxer.nms;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

/**
 * The NMS bootstrap: builds real {@code ServerPlayer}s with a captured
 * connection and registers them through the login pipeline. Ported from
 * Mental's battle-tested FakePlayer (itself from BukkitOldCombatMechanics)
 * and hardened for production use: outbound traffic is CAPTURED for the
 * client brain instead of voided, identity (name/uuid/skin) is caller-owned,
 * and every resolved class/member is memoized — N boxers pay one resolution.
 *
 * <p>Reflection names route through reflection-remapper: identity on
 * Mojang-mapped runtimes (1.20.5+), mapped via the Paper jar's reobf data
 * below that.</p>
 */
public final class NmsBridge {

    private final JavaPlugin plugin;
    private final ReflectionRemapper remapper;
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    public NmsBridge(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.remapper = createRemapper(plugin);
    }

    /** A boxer's skin: the Mojang textures property value + signature. */
    public record SkinTextures(@NotNull String value, @NotNull String signature) {}

    /** Everything the brain needs to drive one spawned player. */
    public record SpawnedPlayer(
            @NotNull Object serverPlayer,
            @NotNull Object gameListener,
            @NotNull Player player,
            int entityId) {}

    /* ------------------------------------------------------------------ */
    /*  Spawn                                                              */
    /* ------------------------------------------------------------------ */

    /** Must run on the global/main thread. */
    public @NotNull SpawnedPlayer spawn(
            @NotNull UUID uuid,
            @NotNull String name,
            @Nullable SkinTextures skin,
            @NotNull Location location,
            @NotNull Consumer<CapturedPacket> packetSink) throws ReflectiveOperationException {
        Object worldServer = handleOf(location.getWorld());
        Object minecraftServer = minecraftServer();
        Object gameProfile = createGameProfile(uuid, name, skin);

        Object serverPlayer = createServerPlayer(minecraftServer, worldServer, gameProfile);
        Object connection = setupConnection(minecraftServer, serverPlayer, packetSink);
        setGameModeSurvival(serverPlayer);
        setPosition(serverPlayer, location);

        fireAsyncPreLogin(uuid, name);
        boolean placed = addToPlayerList(minecraftServer, connection, serverPlayer);

        if (Bukkit.getPlayer(uuid) == null) {
            plugin.getLogger().info("[nms] placeNewPlayer did not register " + name
                    + " — registering directly");
            registerInPlayerList(minecraftServer, serverPlayer, uuid, name);
            placed = false;
        }

        Player bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Bukkit player " + uuid + " not found after placement");
        }
        if (!placed) {
            addToWorld(worldServer, serverPlayer);
        }
        clearSpawnInvulnerability(serverPlayer);
        applySkinParts(serverPlayer);

        // The login pipeline relocates new players to the world spawn; the
        // Bukkit teleport afterwards is the authoritative way to take them
        // to the requested location on every version.
        bukkitPlayer.teleport(location);

        // Modern placeNewPlayer builds its OWN game listener, replacing the
        // bootstrap one — the live listener must be read back off the player
        // or packet dispatch would feed a dead object.
        Object liveListener = readConnectionField(serverPlayer);
        return new SpawnedPlayer(serverPlayer,
                liveListener != null ? liveListener : connectionListenerFallback(connection),
                bukkitPlayer, bukkitPlayer.getEntityId());
    }

    /** Must run on the global/main thread. Idempotent against re-removal. */
    public void remove(@NotNull SpawnedPlayer spawned, @NotNull String quitMessage) {
        Player player = spawned.player();
        try {
            player.kickPlayer(quitMessage);
        } catch (Throwable ignored) {
            // Stubbed connection — fall through to direct list removal.
        }
        // The kick fires PlayerQuitEvent on every healthy path; fire it
        // manually ONLY if the player somehow survived (firing first would
        // double-deliver and other plugins' quit handlers see a not-online
        // player on the second pass).
        if (player.isOnline()) {
            try {
                Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, quitMessage));
            } catch (Throwable ignored) {
                // Some versions require a quit reason component.
            }
        }
        try {
            Object playerList = playerList(minecraftServer());
            Method removeMethod = methodAssignableCached(playerList.getClass(), "remove",
                    spawned.serverPlayer().getClass());
            if (removeMethod != null) {
                removeMethod.invoke(playerList, spawned.serverPlayer());
            }
        } catch (Throwable ignored) {
            // Already removed by the kick path.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Per-tick driving                                                   */
    /* ------------------------------------------------------------------ */

    private volatile Method tickMethod;

    /**
     * Ticks the ServerPlayer. Clientless players are not driven by the
     * connection tick loop (their Connection is never registered with the
     * ServerConnectionListener), so the brain ticks them — Mental's matrix
     * proved this single-ticks timers correctly across the version range.
     */
    public void tick(@NotNull Object serverPlayer) {
        try {
            Method tick = tickMethod;
            if (tick == null) {
                for (String candidate : new String[] {"doTick", "tick", "baseTick"}) {
                    String remapped = remapMethod(serverPlayer.getClass(), candidate);
                    Method method = Reflect.method(serverPlayer.getClass(), remapped);
                    if (method == null) {
                        method = Reflect.method(serverPlayer.getClass(), candidate);
                    }
                    if (method != null && method.getParameterCount() == 0) {
                        tickMethod = tick = method;
                        break;
                    }
                }
            }
            if (tick != null) {
                tick.invoke(serverPlayer);
            }
        } catch (Throwable ignored) {
            // Ticking is best-effort, like the tester's: a single missed
            // tick self-heals next tick.
        }
    }

    private volatile Field hurtMarkedField;
    private volatile boolean hurtMarkedResolved;
    private volatile Constructor<?> velocityFromEntityConstructor;

    /**
     * Vanilla ships a player's own knockback as a SetEntityMotion packet
     * when processing {@code hurtMarked} — but WHERE moved across the range
     * (doTick on most versions, the entity tracker on 1.21.x), and a
     * boxer's doTick ALSO drags its server motion fields, so the flag must
     * be consumed BEFORE the tick or the packet ships one decay stale.
     * This replicates the vanilla processing exactly, every version, ahead
     * of the tick: fire PlayerVelocityEvent (combat pipelines listen to
     * it), honor cancellation (flag stays set, like the tracker), apply a
     * listener-modified velocity, clear the flag, build the same packet
     * vanilla builds. Vanilla's own processing then finds the flag false.
     */
    public @Nullable Object drainHurtMarked(@NotNull Object serverPlayer, @NotNull Player player) {
        try {
            if (!hurtMarkedResolved) {
                hurtMarkedResolved = true;
                // hurtMarked is declared on Entity; spigot-mapped remapping
                // resolves a field only against its DECLARING class, so walk
                // the hierarchy and remap at every level.
                Field field = null;
                for (Class<?> owner = serverPlayer.getClass();
                        owner != null && field == null; owner = owner.getSuperclass()) {
                    field = Reflect.field(owner, remapField(owner, "hurtMarked"));
                }
                if (field == null) {
                    field = Reflect.field(serverPlayer.getClass(), "hurtMarked");
                }
                hurtMarkedField = field;
                Class<?> packetClass = nmsClass(
                        "net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket");
                for (Constructor<?> constructor : packetClass.getConstructors()) {
                    Class<?>[] parameters = constructor.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isInstance(serverPlayer)) {
                        velocityFromEntityConstructor = constructor;
                        break;
                    }
                }
            }
            Field field = hurtMarkedField;
            if (field == null || velocityFromEntityConstructor == null
                    || !field.getBoolean(serverPlayer)) {
                return null;
            }
            Vector velocity = player.getVelocity();
            PlayerVelocityEvent event = new PlayerVelocityEvent(player, velocity.clone());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return null; // flag stays set — the tracker's exact behavior
            }
            if (!velocity.equals(event.getVelocity())) {
                player.setVelocity(event.getVelocity());
            }
            field.setBoolean(serverPlayer, false);
            Object packet = velocityFromEntityConstructor.newInstance(serverPlayer);
            // Vanilla broadcastAndSend ships the same packet to the victim's
            // VIEWERS too — without this, spectators of a boxer would see a
            // once-decayed motion sync instead of the pristine stamp.
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                    sendPacket(viewer, packet);
                }
            }
            return packet;
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** Reflective {@code connection.send(packet)} to a live player. */
    public void sendPacket(@NotNull Player viewer, @NotNull Object packet) {
        try {
            Object handle = handleOf(viewer);
            Object connection = readConnectionField(handle);
            if (connection == null) {
                return;
            }
            for (Method method : connection.getClass().getMethods()) {
                if (method.getParameterCount() == 1
                        && method.getReturnType() == void.class
                        && method.getParameterTypes()[0].isInstance(packet)
                        && method.getParameterTypes()[0].getSimpleName().equals("Packet")) {
                    method.invoke(connection, packet);
                    return;
                }
            }
        } catch (Throwable unsupported) {
            // A missed spectator packet is cosmetic.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  NMS plumbing (FakePlayer lineage)                                  */
    /* ------------------------------------------------------------------ */

    private static volatile ReflectionRemapper sharedRemapper;

    /** One reobf-mappings parse serves every bridge in the JVM. */
    private static ReflectionRemapper createRemapper(JavaPlugin plugin) {
        ReflectionRemapper cached = sharedRemapper;
        if (cached != null) {
            return cached;
        }
        synchronized (NmsBridge.class) {
            if (sharedRemapper == null) {
                try {
                    sharedRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();
                } catch (Throwable mojangMappedRuntime) {
                    plugin.getLogger().info(
                            "No reobf mappings present — Mojang-mapped runtime, using identity remapper.");
                    sharedRemapper = ReflectionRemapper.noop();
                }
            }
            return sharedRemapper;
        }
    }

    public @NotNull Class<?> nmsClass(@NotNull String mojangName) throws ClassNotFoundException {
        Class<?> cached = classCache.get(mojangName);
        if (cached != null) {
            return cached;
        }
        String remapped = remapper.remapClassName(mojangName);
        try {
            Class<?> resolved = Class.forName(remapped, true, Bukkit.getServer().getClass().getClassLoader());
            classCache.put(mojangName, resolved);
            return resolved;
        } catch (ReflectiveOperationException failure) {
            throw new ClassNotFoundException(mojangName, failure);
        }
    }

    public @NotNull String remapMethod(@NotNull Class<?> owner, @NotNull String name, Class<?>... parameterTypes) {
        try {
            return remapper.remapMethodName(owner, name, parameterTypes);
        } catch (Throwable unmappable) {
            return name;
        }
    }

    public @NotNull String remapField(@NotNull Class<?> owner, @NotNull String name) {
        try {
            return remapper.remapFieldName(owner, name);
        } catch (Throwable unmappable) {
            return name;
        }
    }

    /** Mojang-name method resolution with remap + plain fallbacks, memoized. */
    private @Nullable Method methodAssignableCached(Class<?> owner, String mojangName, Class<?>... argumentTypes) {
        String key = owner.getName() + "#" + mojangName + "/" + argumentTypes.length;
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        Method method = Reflect.methodAssignable(owner,
                remapMethod(owner, mojangName, argumentTypes), argumentTypes);
        if (method == null) {
            method = Reflect.methodAssignable(owner, mojangName, argumentTypes);
        }
        if (method != null) {
            methodCache.put(key, method);
        }
        return method;
    }

    public Object minecraftServer() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        Method getter = Reflect.method(server.getClass(), "getServer");
        if (getter == null) {
            throw new NoSuchMethodException("getServer not found on " + server.getClass());
        }
        return getter.invoke(server);
    }

    public Object handleOf(Object craftObject) throws ReflectiveOperationException {
        Method getter = Reflect.method(craftObject.getClass(), "getHandle");
        if (getter == null) {
            throw new NoSuchMethodException("getHandle not found on " + craftObject.getClass());
        }
        return getter.invoke(craftObject);
    }

    private Object playerList(Object minecraftServer) throws ReflectiveOperationException {
        Class<?> serverClass = nmsClass("net.minecraft.server.MinecraftServer");
        Method getter = Reflect.method(serverClass, remapMethod(serverClass, "getPlayerList"));
        if (getter == null) {
            getter = Reflect.method(serverClass, "getPlayerList");
        }
        if (getter == null) {
            throw new NoSuchMethodException("getPlayerList not resolvable on " + serverClass);
        }
        return getter.invoke(minecraftServer);
    }

    private Object createGameProfile(UUID uuid, String name, @Nullable SkinTextures skin)
            throws ReflectiveOperationException {
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        if (skin == null) {
            return profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
        }
        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        Object property = propertyClass.getConstructor(String.class, String.class, String.class)
                .newInstance("textures", skin.value(), skin.signature());
        Class<?> mapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
        Object propertyMap;
        try {
            propertyMap = mapClass.getConstructor().newInstance();
            Method put = Reflect.methodAssignable(mapClass, "put", String.class, propertyClass);
            if (put == null) {
                throw new NoSuchMethodException("PropertyMap.put not found");
            }
            put.invoke(propertyMap, "textures", property);
        } catch (NoSuchMethodException authlib7) {
            // authlib 7 dropped the no-arg constructor: wrap a live guava
            // multimap instead (guava ships with every server).
            Class<?> multimapClass = Class.forName("com.google.common.collect.LinkedHashMultimap");
            Object multimap = multimapClass.getMethod("create").invoke(null);
            Method put = Reflect.methodAssignable(multimap.getClass(), "put",
                    String.class, propertyClass);
            if (put == null) {
                throw new NoSuchMethodException("Multimap.put not found");
            }
            put.invoke(multimap, "textures", property);
            Class<?> guavaMultimap = Class.forName("com.google.common.collect.Multimap");
            propertyMap = mapClass.getConstructor(guavaMultimap).newInstance(multimap);
        }
        // authlib 7 made GameProfile a record whose property map is frozen —
        // the textures must ride the 3-arg constructor. Older authlib lacks
        // it; there the 2-arg profile's map is live and mutated directly.
        try {
            Constructor<?> withProperties =
                    profileClass.getConstructor(UUID.class, String.class, mapClass);
            return withProperties.newInstance(uuid, name, propertyMap);
        } catch (NoSuchMethodException legacyAuthlib) {
            Object profile = profileClass.getConstructor(UUID.class, String.class)
                    .newInstance(uuid, name);
            Method getProperties = Reflect.method(profileClass, "getProperties");
            if (getProperties == null) {
                throw new NoSuchMethodException("GameProfile.getProperties not found");
            }
            Object properties = getProperties.invoke(profile);
            Method legacyPut = Reflect.methodAssignable(properties.getClass(), "put",
                    String.class, propertyClass);
            if (legacyPut == null) {
                throw new NoSuchMethodException("PropertyMap.put not found");
            }
            legacyPut.invoke(properties, "textures", property);
            return profile;
        }
    }

    private Object createServerPlayer(Object minecraftServer, Object worldServer, Object gameProfile)
            throws ReflectiveOperationException {
        Class<?> serverPlayerClass = nmsClass("net.minecraft.server.level.ServerPlayer");
        Class<?> minecraftServerClass = nmsClass("net.minecraft.server.MinecraftServer");
        Class<?> profileClass = gameProfile.getClass();

        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(serverPlayerClass.getConstructors()));
        constructors.sort(Comparator.comparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length < 3
                    || !parameters[0].isAssignableFrom(minecraftServerClass)
                    || !parameters[1].isAssignableFrom(worldServer.getClass())
                    || !parameters[2].isAssignableFrom(profileClass)) {
                continue;
            }
            List<Object> arguments = new ArrayList<>(List.of(minecraftServer, worldServer, gameProfile));
            boolean supported = true;
            for (int i = 3; i < parameters.length; i++) {
                switch (parameters[i].getSimpleName()) {
                    case "ProfilePublicKey" -> arguments.add(null);
                    case "ClientInformation" -> arguments.add(createClientInformation(parameters[i]));
                    default -> supported = false;
                }
                if (!supported) {
                    break;
                }
            }
            if (supported) {
                return constructor.newInstance(arguments.toArray());
            }
        }
        throw new NoSuchMethodException("No compatible ServerPlayer constructor on " + serverPlayerClass);
    }

    /**
     * A ClientInformation whose model customisation shows ALL skin layers
     * (0x7F) — the default cookie renders boxers bald-headed. Built by
     * probing the record constructor; falls back to {@code createDefault}.
     */
    private Object createClientInformation(Class<?> clientInfoClass) throws ReflectiveOperationException {
        try {
            Constructor<?>[] constructors = clientInfoClass.getConstructors();
            for (Constructor<?> constructor : constructors) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length < 6) {
                    continue;
                }
                Object[] arguments = probeDefaults(parameters);
                if (arguments != null) {
                    return constructor.newInstance(arguments);
                }
            }
        } catch (Throwable probeFailure) {
            // Fall through to the vanilla default below.
        }
        Method createDefault = Reflect.method(clientInfoClass,
                remapMethod(clientInfoClass, "createDefault"));
        if (createDefault == null) {
            createDefault = Reflect.method(clientInfoClass, "createDefault");
        }
        if (createDefault == null) {
            throw new NoSuchMethodException("createDefault not found on " + clientInfoClass);
        }
        return createDefault.invoke(null);
    }

    /**
     * Heuristic argument fill for ClientInformation-shaped constructors:
     * language, view distance (the int right after it), then model
     * customisation 0x7F for any later int; enums take canonical defaults;
     * the first boolean (chat colors) is true, the rest false.
     */
    private @Nullable Object[] probeDefaults(Class<?>[] parameters) {
        Object[] arguments = new Object[parameters.length];
        boolean sawInt = false;
        boolean sawBoolean = false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            if (parameter == String.class) {
                arguments[i] = "en_us";
            } else if (parameter == int.class) {
                arguments[i] = sawInt ? 0x7F : 10;
                sawInt = true;
            } else if (parameter == boolean.class) {
                arguments[i] = !sawBoolean;
                sawBoolean = true;
            } else if (parameter.isEnum()) {
                Object constant = enumByNames(parameter, "FULL", "RIGHT", "ALL");
                if (constant == null) {
                    return null;
                }
                arguments[i] = constant;
            } else {
                return null;
            }
        }
        return arguments;
    }

    private static @Nullable Object enumByNames(Class<?> enumClass, String... preferred) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return null;
        }
        for (String name : preferred) {
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
        }
        return constants[0];
    }

    private Object setupConnection(Object minecraftServer, Object serverPlayer,
            Consumer<CapturedPacket> packetSink) throws ReflectiveOperationException {
        Class<?> connectionClass = nmsClass("net.minecraft.network.Connection");
        Class<?> packetFlowClass = nmsClass("net.minecraft.network.protocol.PacketFlow");

        Object packetFlow = Reflect.enumConstant(packetFlowClass,
                remapField(packetFlowClass, "SERVERBOUND"), "SERVERBOUND");
        Object connection = connectionClass.getConstructor(packetFlowClass).newInstance(packetFlow);

        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channel.pipeline().addFirst("simpleboxer-capture", new OutboundCapture(packetSink));
        if (channel.pipeline().get("decoder") == null) {
            channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        }
        if (channel.pipeline().get("encoder") == null) {
            channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        }

        setField(connectionClass, connection, "channel", channel);
        setField(connectionClass, connection, "address", new InetSocketAddress("127.0.0.1", 9999));

        Object listener = createGamePacketListener(minecraftServer, connectionClass, connection, serverPlayer);
        Field connectionField = Reflect.field(serverPlayer.getClass(),
                remapField(serverPlayer.getClass(), "connection"));
        if (connectionField == null) {
            connectionField = Reflect.field(serverPlayer.getClass(), "connection");
        }
        if (connectionField != null) {
            connectionField.set(serverPlayer, listener);
        }

        Method setListener = methodAssignableCached(connectionClass, "setListener", listener.getClass());
        if (setListener != null) {
            setListener.invoke(connection, listener);
        }
        return connection;
    }

    private Object createGamePacketListener(Object minecraftServer, Class<?> connectionClass,
            Object connection, Object serverPlayer) throws ReflectiveOperationException {
        Class<?> listenerClass = nmsClass("net.minecraft.server.network.ServerGamePacketListenerImpl");
        Class<?> minecraftServerClass = nmsClass("net.minecraft.server.MinecraftServer");

        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(listenerClass.getConstructors()));
        constructors.sort(Comparator.comparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length < 3
                    || !parameters[0].isAssignableFrom(minecraftServerClass)
                    || !parameters[1].isAssignableFrom(connectionClass)
                    || !parameters[2].isAssignableFrom(serverPlayer.getClass())) {
                continue;
            }
            List<Object> arguments = new ArrayList<>(List.of(minecraftServer, connection, serverPlayer));
            boolean supported = true;
            for (int i = 3; i < parameters.length; i++) {
                if (parameters[i].getSimpleName().equals("CommonListenerCookie")) {
                    arguments.add(createListenerCookie(parameters[i], serverPlayer));
                } else {
                    supported = false;
                    break;
                }
            }
            if (supported) {
                return constructor.newInstance(arguments.toArray());
            }
        }
        throw new NoSuchMethodException("No compatible ServerGamePacketListenerImpl constructor on " + listenerClass);
    }

    private Object createListenerCookie(Class<?> cookieClass, Object serverPlayer)
            throws ReflectiveOperationException {
        Method profileGetter = methodAssignableCached(serverPlayer.getClass(), "getGameProfile");
        if (profileGetter == null) {
            throw new NoSuchMethodException("getGameProfile not found on " + serverPlayer.getClass());
        }
        Object gameProfile = profileGetter.invoke(serverPlayer);
        Method createInitial = methodAssignableCached(cookieClass, "createInitial",
                gameProfile.getClass(), boolean.class);
        if (createInitial == null) {
            throw new NoSuchMethodException("createInitial not found on " + cookieClass);
        }
        return createInitial.invoke(null, gameProfile, false);
    }

    private void setGameModeSurvival(Object serverPlayer) throws ReflectiveOperationException {
        Class<?> gameTypeClass = nmsClass("net.minecraft.world.level.GameType");
        Object survival = Reflect.enumConstant(gameTypeClass,
                remapField(gameTypeClass, "SURVIVAL"), "SURVIVAL");
        Method setGameMode = methodAssignableCached(serverPlayer.getClass(), "setGameMode", gameTypeClass);
        if (setGameMode != null) {
            setGameMode.invoke(serverPlayer, survival);
        }
    }

    private void setPosition(Object serverPlayer, Location location) throws ReflectiveOperationException {
        setPosition(serverPlayer, location.getX(), location.getY(), location.getZ());
    }

    /** Direct server-side position write ({@code Entity.setPos}). */
    public void setPosition(@NotNull Object serverPlayer, double x, double y, double z)
            throws ReflectiveOperationException {
        Class<?> entityClass = nmsClass("net.minecraft.world.entity.Entity");
        Method setPos = methodAssignableCached(entityClass, "setPos",
                double.class, double.class, double.class);
        if (setPos != null) {
            setPos.invoke(serverPlayer, x, y, z);
        }
    }

    private void fireAsyncPreLogin(UUID uuid, String name) {
        try {
            InetAddress address = InetAddress.getByName("127.0.0.1");
            @SuppressWarnings("deprecation") // legacy ctor works across the whole supported range
            AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, uuid);
            Thread async = new Thread(() -> Bukkit.getPluginManager().callEvent(event),
                    "simpleboxer-prelogin");
            async.start();
            async.join(5000L);
        } catch (Exception failure) {
            plugin.getLogger().warning("Pre-login event for boxer failed: " + failure);
        }
    }

    private boolean addToPlayerList(Object minecraftServer, Object connection, Object serverPlayer)
            throws ReflectiveOperationException {
        Object playerList = playerList(minecraftServer);
        Class<?> playerListClass = nmsClass("net.minecraft.server.players.PlayerList");
        String placeName = remapMethod(playerListClass, "placeNewPlayer",
                connection.getClass(), serverPlayer.getClass());

        for (Method method : playerListClass.getMethods()) {
            if (!method.getName().equals(placeName) && !method.getName().equals("placeNewPlayer")) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())
                    && parameters[2].getSimpleName().equals("CommonListenerCookie")) {
                method.invoke(playerList, connection, serverPlayer,
                        createListenerCookie(parameters[2], serverPlayer));
                return true;
            }
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())) {
                method.invoke(playerList, connection, serverPlayer);
                return true;
            }
        }
        return false;
    }

    /**
     * Direct PlayerList registration (no login pipeline) — the fallback when
     * placeNewPlayer's shape is unknown. Must also fill the lowercased
     * by-NAME map or name-targeted commands and getPlayerExact miss the
     * player.
     */
    private void registerInPlayerList(Object minecraftServer, Object serverPlayer, UUID uuid, String name)
            throws ReflectiveOperationException {
        Object playerList = playerList(minecraftServer);
        Class<?> playerListClass = nmsClass("net.minecraft.server.players.PlayerList");

        Method load = methodAssignableCached(playerListClass, "load", serverPlayer.getClass());
        if (load != null) {
            try {
                load.invoke(playerList, serverPlayer);
            } catch (Throwable failure) {
                plugin.getLogger().info("[nms] PlayerList.load failed (" + failure.getCause()
                        + ") — continuing with raw registration");
            }
        }

        Field playersField = Reflect.field(playerListClass, remapField(playerListClass, "players"));
        if (playersField != null) {
            @SuppressWarnings("unchecked")
            List<Object> players = (List<Object>) playersField.get(playerList);
            if (!players.contains(serverPlayer)) {
                players.add(serverPlayer);
            }
        }
        Field byUuid = uuidMapField(playerListClass);
        if (byUuid != null) {
            @SuppressWarnings("unchecked")
            Map<UUID, Object> map = (Map<UUID, Object>) byUuid.get(playerList);
            map.put(uuid, serverPlayer);
        }
        Field byName = playerMapField(playerListClass, String.class, serverPlayer.getClass());
        if (byName != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) byName.get(playerList);
            map.put(name.toLowerCase(Locale.ROOT), serverPlayer);
        }
    }

    private void addToWorld(Object worldServer, Object serverPlayer) {
        for (String candidate : new String[] {"addNewPlayer", "addPlayer", "addFreshEntity", "addEntity"}) {
            try {
                Method method = methodAssignableCached(worldServer.getClass(), candidate,
                        serverPlayer.getClass());
                if (method != null) {
                    method.invoke(worldServer, serverPlayer);
                    return;
                }
            } catch (Throwable next) {
                // try the next candidate
            }
        }
        plugin.getLogger().warning("Could not add boxer to the world directly");
    }

    /**
     * Join protection moved across the version range: through 1.21.1 it is
     * {@code ServerPlayer.spawnInvulnerableTime} (60 ticks); from 1.21.2 the
     * player is invulnerable until their client confirms loading — state a
     * clientless player can only time out. Clear whichever exists, reading
     * the LIVE listener off the player (placeNewPlayer replaced ours).
     */
    private void clearSpawnInvulnerability(Object serverPlayer) {
        setFieldQuietly(serverPlayer, "spawnInvulnerableTime", 0);
        setFieldQuietly(serverPlayer, "clientLoaded", true);
        setFieldQuietly(serverPlayer, "clientLoadedTimeoutTimer", 0);
        Object liveListener = readConnectionField(serverPlayer);
        if (liveListener != null) {
            setFieldQuietly(liveListener, "clientLoadedTimeoutTimer", 0);
            setFieldQuietly(liveListener, "waitingForRespawn", false);
        }
    }

    /** All skin layers visible — cosmetic, best-effort on every version. */
    private void applySkinParts(Object serverPlayer) {
        try {
            for (Method method : serverPlayer.getClass().getMethods()) {
                if (method.getParameterCount() != 1) {
                    continue;
                }
                String parameterName = method.getParameterTypes()[0].getSimpleName();
                boolean optionsShape = parameterName.equals("ClientInformation")
                        || parameterName.equals("ServerboundClientInformationPacket")
                        || parameterName.equals("PacketPlayInSettings");
                if (!optionsShape) {
                    continue;
                }
                Object argument = buildOptionsArgument(method.getParameterTypes()[0]);
                if (argument != null) {
                    method.invoke(serverPlayer, argument);
                    return;
                }
            }
        } catch (Throwable cosmetic) {
            // Bald boxers are a cosmetic regression, never a functional one.
        }
    }

    private @Nullable Object buildOptionsArgument(Class<?> optionsClass) {
        try {
            if (optionsClass.getSimpleName().equals("ClientInformation")) {
                return createClientInformation(optionsClass);
            }
            for (Constructor<?> constructor : optionsClass.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length < 5) {
                    continue;
                }
                Object[] arguments = probeDefaults(parameters);
                if (arguments != null) {
                    return constructor.newInstance(arguments);
                }
            }
        } catch (Throwable unsupported) {
            // Fall through.
        }
        return null;
    }

    public @Nullable Object readConnectionField(@NotNull Object serverPlayer) {
        try {
            Field connectionField = Reflect.field(serverPlayer.getClass(),
                    remapField(serverPlayer.getClass(), "connection"));
            if (connectionField == null) {
                connectionField = Reflect.field(serverPlayer.getClass(), "connection");
            }
            return connectionField == null ? null : connectionField.get(serverPlayer);
        } catch (Throwable absent) {
            return null;
        }
    }

    private Object connectionListenerFallback(Object connection) throws ReflectiveOperationException {
        Method getListener = methodAssignableCached(connection.getClass(), "getPacketListener");
        if (getListener != null) {
            Object listener = getListener.invoke(connection);
            if (listener != null) {
                return listener;
            }
        }
        throw new IllegalStateException("No live game packet listener after placement");
    }

    private void setFieldQuietly(Object target, String mojangName, Object value) {
        try {
            Field field = Reflect.field(target.getClass(), remapField(target.getClass(), mojangName));
            if (field == null) {
                field = Reflect.field(target.getClass(), mojangName);
            }
            if (field != null) {
                field.set(target, value);
            }
        } catch (Throwable ignored) {
            // Best effort — the field may not exist on this version.
        }
    }

    private void setField(Class<?> owner, Object target, String mojangName, Object value)
            throws ReflectiveOperationException {
        Field field = Reflect.field(owner, remapField(owner, mojangName));
        if (field == null) {
            field = Reflect.field(owner, mojangName);
        }
        if (field == null) {
            throw new NoSuchFieldException(mojangName + " not found on " + owner);
        }
        field.set(target, value);
    }

    private static @Nullable Field uuidMapField(Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            if (field.getGenericType().getTypeName().contains("UUID")) {
                return field;
            }
        }
        return null;
    }

    /** The Map field keyed by {@code keyType} whose values hold a ServerPlayer. */
    private static @Nullable Field playerMapField(Class<?> owner, Class<?> keyType, Class<?> playerClass) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())
                    || !(field.getGenericType() instanceof ParameterizedType parameterized)) {
                continue;
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 2
                    && arguments[0] == keyType
                    && arguments[1] instanceof Class<?> valueType
                    && valueType.isAssignableFrom(playerClass)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}

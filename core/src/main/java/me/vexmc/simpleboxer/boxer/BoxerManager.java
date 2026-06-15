package me.vexmc.simpleboxer.boxer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerService;
import me.vexmc.simpleboxer.api.BoxerSpawnRequest;
import me.vexmc.simpleboxer.api.event.BoxerRemoveEvent;
import me.vexmc.simpleboxer.api.event.BoxerSpawnEvent;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.scheduling.TaskHandle;
import me.vexmc.simpleboxer.config.ConfigStore;
import me.vexmc.simpleboxer.identity.SkinService;
import me.vexmc.simpleboxer.identity.TabConcealer;
import me.vexmc.simpleboxer.nms.CapturedPacket;
import me.vexmc.simpleboxer.nms.NmsBridge;
import me.vexmc.simpleboxer.nms.PacketIO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boxer registry and lifecycle owner. Spawning runs on the thread that
 * owns the spawn location's region (the NMS bootstrap's requirement — the main
 * thread on classic servers, the location's region thread on Folia); each
 * boxer then ticks on its own owning-thread task, and shutdown despawns
 * everyone — boxers are ephemeral test fixtures, never persisted.
 */
public final class BoxerManager implements BoxerService {

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final ConfigStore config;
    private final NmsBridge bridge;
    private final PacketIO packetIO;
    private final SkinService skins;
    private final TabConcealer tabConcealer;
    private final Map<UUID, BoxerImpl> byUuid = new ConcurrentHashMap<>();
    private final Map<String, BoxerImpl> byName = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> tickTasks = new ConcurrentHashMap<>();

    public BoxerManager(@NotNull JavaPlugin plugin, @NotNull Scheduling scheduling,
            @NotNull ConfigStore config) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.config = config;
        this.bridge = new NmsBridge(plugin);
        this.packetIO = new PacketIO(bridge);
        this.skins = new SkinService(scheduling, plugin.getDataFolder().toPath(), plugin.getLogger());
        this.tabConcealer = new TabConcealer(bridge, plugin.getLogger());
    }

    public @NotNull TabConcealer tabConcealer() {
        return tabConcealer;
    }

    @Override
    public @NotNull CompletableFuture<Boxer> spawn(@NotNull BoxerSpawnRequest request) {
        CompletableFuture<Boxer> future = new CompletableFuture<>();
        if (request.skinOwner() != null) {
            // Skin first (async HTTP), then the main-thread bootstrap — the
            // textures must be on the GameProfile BEFORE placement so the
            // join broadcast carries them.
            skins.lookup(request.skinOwner()).whenComplete((textures, lookupFailure) -> {
                NmsBridge.SkinTextures skin =
                        textures == null ? null : textures.orElse(null);
                dispatchSpawn(request, skin, future);
            });
        } else {
            dispatchSpawn(request, null, future);
        }
        return future;
    }

    private void dispatchSpawn(BoxerSpawnRequest request,
            @Nullable NmsBridge.SkinTextures skin, CompletableFuture<Boxer> future) {
        Runnable work = () -> {
            try {
                future.complete(spawnNow(request, skin));
            } catch (Throwable failure) {
                // The console logger always hears about it — a command
                // sender (rcon especially) may be gone by completion time.
                Throwable root = failure;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                plugin.getLogger().warning("Spawn of '" + request.name() + "' failed: " + root);
                future.completeExceptionally(failure);
            }
        };
        // The NMS bootstrap (player-list placement, ServerLevel writes) is only
        // legal on the thread that owns the spawn location's region: the main
        // thread on classic servers, the location's region thread on Folia.
        // Run inline when the caller is already there — a same-thread waiter
        // blocking on the returned future would deadlock on a scheduled hop;
        // otherwise hop to the owning region.
        if (scheduling.ownsRegion(request.location())) {
            work.run();
        } else {
            scheduling.runAt(request.location(), work);
        }
    }

    /** Runs on the thread owning the spawn location's region (see {@link #dispatchSpawn}). */
    private @NotNull Boxer spawnNow(@NotNull BoxerSpawnRequest request,
            @Nullable NmsBridge.SkinTextures skin) throws Exception {
        String key = request.name().toLowerCase(Locale.ROOT);
        if (byName.containsKey(key)) {
            throw new IllegalStateException("A boxer named " + request.name() + " already exists");
        }
        Player collision = Bukkit.getPlayerExact(request.name());
        if (collision != null) {
            throw new IllegalStateException(request.name() + " is an online player");
        }

        UUID uuid = UUID.randomUUID();
        // The sink outlives this frame; hand packets to the boxer once the
        // construction below completes. Packets written DURING placement
        // (the join barrage) buffer here and drain on the first tick.
        List<CapturedPacket> preSpawn = new ArrayList<>();
        BoxerImpl[] holder = new BoxerImpl[1];

        NmsBridge.SpawnedPlayer spawned = bridge.spawn(uuid, request.name(), skin,
                request.location(), packet -> {
                    BoxerImpl boxer = holder[0];
                    if (boxer != null) {
                        boxer.onOutboundPacket(packet);
                    } else {
                        synchronized (preSpawn) {
                            preSpawn.add(packet);
                        }
                    }
                });

        BoxerImpl boxer = new BoxerImpl(request.name(), uuid, request.settings(), this,
                bridge, packetIO, spawned, request.location(), plugin.getLogger());
        synchronized (preSpawn) {
            preSpawn.forEach(boxer::onOutboundPacket);
            preSpawn.clear();
        }
        holder[0] = boxer;

        // Answer the 1.21.4+ client-loaded handshake before the brain's
        // first packets — a real client answers when its level renders, and
        // an unanswered gate re-armed by a respawn silently drops sprint
        // commands and attacks for three seconds.
        boxer.declareClientLoaded();

        byUuid.put(uuid, boxer);
        byName.put(key, boxer);

        // Tireless from the first tick: full food, full saturation.
        spawned.player().setFoodLevel(20);
        spawned.player().setSaturation(20.0f);

        // Off the tab list (configurable). The legacy info-remove waits for
        // viewers' skin load; the modern LISTED=false is immediate.
        if (config.snapshot().hideFromTab()) {
            if (tabConcealer.usesLegacyPath()) {
                scheduling.runLaterOn(spawned.player(), 40L,
                        () -> tabConcealer.hide(spawned.player()), () -> {});
            } else {
                tabConcealer.hide(spawned.player());
            }
        }

        if (request.targetName() != null) {
            Player target = Bukkit.getPlayerExact(request.targetName());
            if (target != null) {
                boxer.setTarget(target);
            }
        }

        TaskHandle task = scheduling.repeatOn(spawned.player(), 1L, 1L,
                boxer::tick, () -> removeInternal(boxer, "boxer entity retired"));
        tickTasks.put(uuid, task);

        Bukkit.getPluginManager().callEvent(new BoxerSpawnEvent(boxer));
        plugin.getLogger().info("Spawned boxer " + request.name() + " (" + uuid + ")");
        return boxer;
    }

    void remove(@NotNull BoxerImpl boxer) {
        scheduling.runOn(boxer.player(),
                () -> removeInternal(boxer, "boxer removed"),
                () -> removeInternal(boxer, "boxer removed"));
    }

    /** Owning/main thread. Idempotent. */
    private void removeInternal(@NotNull BoxerImpl boxer, @NotNull String reason) {
        if (boxer.isRemoved()) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new BoxerRemoveEvent(boxer));
        TaskHandle task = tickTasks.remove(boxer.uuid());
        if (task != null) {
            task.cancel();
        }
        boxer.despawn(reason);
        byUuid.remove(boxer.uuid());
        byName.remove(boxer.name().toLowerCase(Locale.ROOT));
        plugin.getLogger().info("Removed boxer " + boxer.name());
    }

    @Override
    public @NotNull Optional<Boxer> byName(@NotNull String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public @NotNull Optional<Boxer> byUuid(@NotNull UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @NotNull Optional<BoxerImpl> byUuidInternal(@NotNull UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @Override
    public @NotNull Collection<Boxer> all() {
        return Collections.unmodifiableCollection(byUuid.values());
    }

    @Override
    public boolean isBoxer(@NotNull UUID uuid) {
        return byUuid.containsKey(uuid);
    }

    @Override
    public void removeAll() {
        for (BoxerImpl boxer : List.copyOf(byUuid.values())) {
            removeInternal(boxer, "boxer removed");
        }
    }

    /** Plugin disable: synchronous despawn-all on the main thread. */
    public void shutdown() {
        removeAll();
    }
}

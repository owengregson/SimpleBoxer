package me.vexmc.simpleboxer.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * The boxer registry. Obtain via Bukkit's {@code ServicesManager} or from
 * the SimpleBoxer plugin instance.
 */
public interface BoxerService {

    /**
     * Spawns a boxer. Completes on the main thread once the player entity is
     * live (skin application may finish a moment later if the lookup is
     * cold). Fails if the name collides with an online player or boxer.
     */
    @NotNull CompletableFuture<Boxer> spawn(@NotNull BoxerSpawnRequest request);

    @NotNull Optional<Boxer> byName(@NotNull String name);

    @NotNull Optional<Boxer> byUuid(@NotNull UUID uuid);

    @NotNull Collection<Boxer> all();

    boolean isBoxer(@NotNull UUID uuid);

    /** Removes every boxer (shutdown, /boxer remove all). */
    void removeAll();
}

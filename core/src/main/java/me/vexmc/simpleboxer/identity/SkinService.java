package me.vexmc.simpleboxer.identity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.nms.NmsBridge;
import org.jetbrains.annotations.NotNull;

/**
 * Mojang skin lookups with a disk cache. A boxer wearing "Notch's skin" is
 * cosmetic sugar for test sessions — lookups are best-effort, failures fall
 * back to the default steve/alex (decided by the boxer's random UUID parity,
 * exactly as vanilla decides it for textureless profiles).
 */
public final class SkinService {

    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7);
    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final Scheduling scheduling;
    private final Logger logger;
    private final Path cacheDirectory;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<NmsBridge.SkinTextures>>>
            inFlight = new ConcurrentHashMap<>();

    public SkinService(@NotNull Scheduling scheduling, @NotNull Path dataDirectory,
            @NotNull Logger logger) {
        this.scheduling = scheduling;
        this.logger = logger;
        this.cacheDirectory = dataDirectory.resolve("skins");
    }

    /** Resolves off-thread; the future completes on the lookup thread. */
    public @NotNull CompletableFuture<Optional<NmsBridge.SkinTextures>> lookup(@NotNull String ownerName) {
        String key = ownerName.toLowerCase(Locale.ROOT);
        return inFlight.computeIfAbsent(key, missing -> {
            CompletableFuture<Optional<NmsBridge.SkinTextures>> future = new CompletableFuture<>();
            scheduling.runAsync(() -> {
                try {
                    future.complete(resolve(key));
                } catch (Throwable failure) {
                    logger.warning("Skin lookup for '" + ownerName + "' failed: " + failure);
                    future.complete(Optional.empty());
                } finally {
                    inFlight.remove(key);
                }
            });
            return future;
        });
    }

    private Optional<NmsBridge.SkinTextures> resolve(String key) throws IOException {
        Optional<NmsBridge.SkinTextures> cached = readCache(key);
        if (cached.isPresent()) {
            return cached;
        }
        String uuidBody = httpGet(UUID_URL + key);
        if (uuidBody == null || uuidBody.isBlank()) {
            return Optional.empty(); // unknown account
        }
        String id = JsonParser.parseString(uuidBody).getAsJsonObject().get("id").getAsString();
        String profileBody = httpGet(PROFILE_URL + id + "?unsigned=false");
        if (profileBody == null || profileBody.isBlank()) {
            return Optional.empty();
        }
        JsonArray properties = JsonParser.parseString(profileBody).getAsJsonObject()
                .getAsJsonArray("properties");
        for (int i = 0; i < properties.size(); i++) {
            JsonObject property = properties.get(i).getAsJsonObject();
            if ("textures".equals(property.get("name").getAsString())) {
                NmsBridge.SkinTextures textures = new NmsBridge.SkinTextures(
                        property.get("value").getAsString(),
                        property.has("signature") ? property.get("signature").getAsString() : "");
                writeCache(key, textures);
                return Optional.of(textures);
            }
        }
        return Optional.empty();
    }

    private Optional<NmsBridge.SkinTextures> readCache(String key) {
        Path file = cacheDirectory.resolve(key + ".json");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (System.currentTimeMillis() - json.get("fetchedAtMs").getAsLong() > CACHE_TTL_MS) {
                return Optional.empty();
            }
            return Optional.of(new NmsBridge.SkinTextures(
                    json.get("value").getAsString(), json.get("signature").getAsString()));
        } catch (Throwable corrupt) {
            return Optional.empty();
        }
    }

    private void writeCache(String key, NmsBridge.SkinTextures textures) {
        try {
            Files.createDirectories(cacheDirectory);
            JsonObject json = new JsonObject();
            json.addProperty("value", textures.value());
            json.addProperty("signature", textures.signature());
            json.addProperty("fetchedAtMs", System.currentTimeMillis());
            Files.writeString(cacheDirectory.resolve(key + ".json"), json.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            logger.fine("Could not cache skin " + key + ": " + unwritable);
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                return null;
            }
            try (var stream = connection.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}

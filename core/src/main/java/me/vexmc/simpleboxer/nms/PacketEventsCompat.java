package me.vexmc.simpleboxer.nms;

import java.util.UUID;
import java.util.logging.Logger;
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
 * alone. Entirely reflective and best-effort — with PacketEvents absent (or
 * shaded under a relocated package), every method is a silent no-op.</p>
 */
final class PacketEventsCompat {

    private final Logger logger;
    private final @Nullable Class<?> packetEventsClass;

    PacketEventsCompat(@NotNull Logger logger) {
        this.logger = logger;
        Class<?> resolved = null;
        try {
            resolved = Class.forName("com.github.retrooper.packetevents.PacketEvents");
        } catch (Throwable absent) {
            // PacketEvents not present (or not visible to this classloader).
        }
        this.packetEventsClass = resolved;
    }

    boolean present() {
        return packetEventsClass != null;
    }

    /**
     * Register the boxer's fake channel so PacketEvents recognises and skips it.
     * Must run before the join event (i.e. before {@code placeNewPlayer}).
     */
    void markFakeChannel(@NotNull UUID uuid, @NotNull Object channel) {
        Object protocolManager = protocolManager();
        if (protocolManager == null) {
            return;
        }
        try {
            protocolManager.getClass()
                    .getMethod("setChannel", UUID.class, Object.class)
                    .invoke(protocolManager, uuid, channel);
        } catch (Throwable failure) {
            logger.fine("PacketEvents fake-channel registration failed for " + uuid + ": " + failure);
        }
    }

    /** Drop the boxer's channel from PacketEvents' map on despawn. */
    void forgetFakeChannel(@NotNull UUID uuid) {
        Object protocolManager = protocolManager();
        if (protocolManager == null) {
            return;
        }
        try {
            protocolManager.getClass()
                    .getMethod("removeChannelById", UUID.class)
                    .invoke(protocolManager, uuid);
        } catch (Throwable failure) {
            logger.fine("PacketEvents fake-channel cleanup failed for " + uuid + ": " + failure);
        }
    }

    /** The live {@code ProtocolManager}, or null if PacketEvents is absent or not yet initialised. */
    private @Nullable Object protocolManager() {
        if (packetEventsClass == null) {
            return null;
        }
        try {
            Object api = packetEventsClass.getMethod("getAPI").invoke(null);
            if (api == null) {
                return null; // present but not built yet
            }
            return api.getClass().getMethod("getProtocolManager").invoke(api);
        } catch (Throwable unavailable) {
            return null;
        }
    }
}

package me.vexmc.simpleboxer.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import me.vexmc.simpleboxer.nms.NmsBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hides boxers from the tab list while keeping them fully real serverside —
 * command tab-completion still offers them (the completion list is built
 * from the player list, not the client's tab UI).
 *
 * <p>Listing is per-VIEWER on every version. 1.19.3+ has the clean
 * primitive: {@code viewer.unlistPlayer(boxer)} flips the info entry's
 * LISTED flag for that viewer — the entry (and the skin) survives. Older
 * servers get the classic NPC technique, an info-REMOVE packet per viewer
 * sent after the skin has had time to load; players who join later see the
 * default skin, the known tradeoff of that protocol era. Both paths need
 * re-hiding for every new joiner.</p>
 */
public final class TabConcealer {

    private final NmsBridge bridge;
    private final Logger logger;
    private final @Nullable Method unlistPlayer;

    public TabConcealer(@NotNull NmsBridge bridge, @NotNull Logger logger) {
        this.bridge = bridge;
        this.logger = logger;
        Method found = null;
        try {
            found = Player.class.getMethod("unlistPlayer", Player.class);
        } catch (NoSuchMethodException pre1193) {
            // Legacy packet path below.
        }
        this.unlistPlayer = found;
    }

    /** Hide from every current viewer. Main thread. */
    public void hide(@NotNull Player boxer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(boxer.getUniqueId())) {
                hideFrom(boxer, viewer);
            }
        }
    }

    /** Hide from one viewer (current or late joiner). Main thread. */
    public void hideFrom(@NotNull Player boxer, @NotNull Player viewer) {
        if (unlistPlayer != null) {
            try {
                unlistPlayer.invoke(viewer, boxer);
                return;
            } catch (ReflectiveOperationException failure) {
                logger.warning("unlistPlayer failed, falling back to info-remove: " + failure);
            }
        }
        sendInfoRemove(boxer, viewer);
    }

    /** Legacy viewers need the skin loaded before the entry disappears. */
    public boolean usesLegacyPath() {
        return unlistPlayer == null;
    }

    private void sendInfoRemove(Player boxer, Player viewer) {
        try {
            Object boxerHandle = bridge.handleOf(boxer);
            Class<?> packetClass = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket");
            Class<?> actionClass = null;
            for (Class<?> nested : packetClass.getDeclaredClasses()) {
                if (nested.isEnum()) {
                    actionClass = nested;
                    break;
                }
            }
            if (actionClass == null) {
                throw new ClassNotFoundException("PlayerInfo action enum");
            }
            // REMOVE_PLAYER is the last constant on every legacy version
            // (ADD, UPDATE_GAME_MODE, UPDATE_LATENCY, UPDATE_DISPLAY_NAME, REMOVE).
            Object[] constants = actionClass.getEnumConstants();
            Object removeAction = constants[constants.length - 1];

            Object packet = null;
            for (Constructor<?> constructor : packetClass.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == actionClass
                        && parameters[1].isArray()) {
                    Object array = Array.newInstance(parameters[1].getComponentType(), 1);
                    Array.set(array, 0, boxerHandle);
                    packet = constructor.newInstance(removeAction, array);
                    break;
                }
            }
            if (packet == null) {
                throw new NoSuchMethodException("No (Action, ServerPlayer[]) constructor");
            }
            sendTo(viewer, packet);
        } catch (Throwable failure) {
            logger.warning("Tab concealment failed (boxer stays listed): " + failure);
        }
    }

    private void sendTo(Player viewer, Object packet) throws ReflectiveOperationException {
        Object handle = bridge.handleOf(viewer);
        Object connection = bridge.readConnectionField(handle);
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
    }
}

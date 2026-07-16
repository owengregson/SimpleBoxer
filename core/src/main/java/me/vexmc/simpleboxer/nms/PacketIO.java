package me.vexmc.simpleboxer.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the serverbound packets a real client would send and dispatches
 * them through the boxer's own game listener — the same handlers, the same
 * validation, the same Bukkit events a socket delivers. Decodes the few
 * clientbound packets the brain reacts to (velocity, teleports, explosions),
 * unwrapping 1.19.4+ bundles.
 *
 * <p>Every shape is probed once and memoized. Spigot-mapped member names
 * only exist through 1.20.4 — anything newer (PositionMoveRotation, the
 * Vec3 motion field, the compacted PlayerCommand action enum) runs on a
 * Mojang-mapped server by construction, so modern shapes use Mojang names
 * directly and legacy shapes route through the remapper.</p>
 */
public final class PacketIO {

    private final NmsBridge bridge;

    public PacketIO(@NotNull NmsBridge bridge) throws ReflectiveOperationException {
        this.bridge = bridge;
        resolve();
    }

    /* ------------------------------------------------------------------ */
    /*  Resolution                                                         */
    /* ------------------------------------------------------------------ */

    private Constructor<?> posRotConstructor;
    private boolean posRotHasCollisionFlag;
    private Constructor<?> statusOnlyConstructor;
    private boolean statusOnlyHasCollisionFlag;
    private Constructor<?> acceptTeleportConstructor;
    private Constructor<?> playerCommandConstructor;
    private boolean playerCommandHasData;
    private boolean playerCommandTakesEntity;
    private Object startSprinting;
    private Object stopSprinting;
    private @Nullable Method attackFactory;
    private @Nullable Constructor<?> attackConstructor;
    private Constructor<?> swingConstructor;
    private Object mainHand;
    private Constructor<?> clientCommandConstructor;
    private Object performRespawn;

    // Item-use packets (feature: rod/pot/blockhit/eat). Modern-first: absent
    // shapes leave the factory null and the routine self-disables via its
    // capability flag. Probed once in resolve() and memoized.
    private @Nullable Constructor<?> setCarriedItemConstructor;
    private @Nullable Object offHand;
    private @Nullable Constructor<?> useItemConstructor;
    private boolean useItemHasSequence;
    private boolean useItemHasRotation;
    private @Nullable Constructor<?> playerActionConstructor;
    private boolean playerActionHasSequence;
    private @Nullable Object releaseUseItemAction;
    private @Nullable Object blockPosOrigin;
    private @Nullable Object directionDown;
    // Spigot's anti-spam wall-clock field on the use-item / player-action
    // packets ("timestamp", a patch-added public long — never remapped). The
    // network decode constructor stamps it for every real client packet; the
    // direct constructors leave it 0, which PlayerConnection.checkLimit reads
    // as a flood and silently drops after its 9-packet lifetime grace. Null
    // where a build does not carry the patch (the stamp is then skipped).
    private @Nullable Field useItemTimestamp;
    private @Nullable Field playerActionTimestamp;

    private @Nullable Constructor<?> inputRecordConstructor;
    private @Nullable Constructor<?> inputPacketConstructor;
    private @Nullable Constructor<?> playerLoadedConstructor;

    private @Nullable Class<?> keepAlivePacketClass;
    private @Nullable Field keepAliveId;
    private @Nullable Constructor<?> keepAliveResponseConstructor;

    private Class<?> motionPacketClass;
    private Field motionId;
    private Field @Nullable [] motionInts;
    private @Nullable Field motionVec;
    private Class<?> positionPacketClass;
    private Class<?> explodePacketClass;
    private @Nullable Class<?> bundlePacketClass;
    private @Nullable Method bundleSubPackets;

    private Field vecX;
    private Field vecY;
    private Field vecZ;

    private final Map<Class<?>, Method> handleMethods = new ConcurrentHashMap<>();

    private void resolve() throws ReflectiveOperationException {
        Class<?> moveClass = bridge.nmsClass("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket");
        for (Class<?> nested : moveClass.getDeclaredClasses()) {
            for (Constructor<?> constructor : nested.getConstructors()) {
                Class<?>[] p = constructor.getParameterTypes();
                if (matches(p, double.class, double.class, double.class,
                        float.class, float.class, boolean.class)) {
                    posRotConstructor = constructor;
                    posRotHasCollisionFlag = false;
                } else if (matches(p, double.class, double.class, double.class,
                        float.class, float.class, boolean.class, boolean.class)) {
                    posRotConstructor = constructor;
                    posRotHasCollisionFlag = true;
                } else if (matches(p, boolean.class)) {
                    statusOnlyConstructor = constructor;
                    statusOnlyHasCollisionFlag = false;
                } else if (matches(p, boolean.class, boolean.class)) {
                    statusOnlyConstructor = constructor;
                    statusOnlyHasCollisionFlag = true;
                }
            }
        }
        if (posRotConstructor == null) {
            throw new NoSuchMethodException("MovePlayerPacket.PosRot constructor not found");
        }

        Class<?> acceptClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket");
        acceptTeleportConstructor = acceptClass.getConstructor(int.class);

        Class<?> commandClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket");
        Class<?> entityBaseClass = bridge.nmsClass("net.minecraft.world.entity.Entity");
        Class<?> actionClass = nestedEnum(commandClass, "Action");
        startSprinting = sprintAction(actionClass, true);
        stopSprinting = sprintAction(actionClass, false);
        // Two parameter eras: 1.17 takes the Entity itself, newer versions
        // take the raw entity id; either may carry a trailing data int.
        for (Constructor<?> constructor : commandClass.getConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            boolean idShape = p.length >= 2 && p[0] == int.class && p[1] == actionClass;
            boolean entityShape = p.length >= 2
                    && p[0].isAssignableFrom(entityBaseClass) && p[1] == actionClass;
            if (!idShape && !entityShape) {
                continue;
            }
            if (p.length == 2 || (p.length == 3 && p[2] == int.class)) {
                playerCommandConstructor = constructor;
                playerCommandHasData = p.length == 3;
                playerCommandTakesEntity = entityShape;
                if (p.length == 2) {
                    break; // prefer the dataless shape when both exist
                }
            }
        }
        if (playerCommandConstructor == null) {
            throw new NoSuchMethodException("PlayerCommandPacket constructor not found");
        }

        // Attack changed shape in 26.x: the InteractPacket action union (with
        // its createAttackPacket factory) split into a dedicated record,
        // ServerboundAttackPacket(int entityId).
        Class<?> interactClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ServerboundInteractPacket");
        Class<?> entityClass = bridge.nmsClass("net.minecraft.world.entity.Entity");
        String attackName = bridge.remapMethod(interactClass, "createAttackPacket",
                entityClass, boolean.class);
        attackFactory = Reflect.methodAssignable(interactClass, attackName, entityClass, boolean.class);
        if (attackFactory == null) {
            attackFactory = Reflect.methodAssignable(interactClass, "createAttackPacket",
                    entityClass, boolean.class);
        }
        if (attackFactory == null) {
            Class<?> attackClass = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundAttackPacket");
            attackConstructor = attackClass.getConstructor(int.class);
        }

        Class<?> handClass = bridge.nmsClass("net.minecraft.world.InteractionHand");
        mainHand = handClass.getEnumConstants()[0]; // MAIN_HAND is ordinal 0 on every version
        Class<?> swingClass = bridge.nmsClass("net.minecraft.network.protocol.game.ServerboundSwingPacket");
        swingConstructor = swingClass.getConstructor(handClass);

        Class<?> clientCommandClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ServerboundClientCommandPacket");
        Class<?> respawnActionClass = nestedEnum(clientCommandClass, "Action");
        performRespawn = enumByNameContains(respawnActionClass, "RESPAWN", 0);
        clientCommandConstructor = clientCommandClass.getConstructor(respawnActionClass);

        // 1.21.2+ streams the whole keyboard as an Input record (the
        // pre-1.21.2 packet of the same name is the vehicle-steering float
        // form — the record class gates the lookup), and 1.21.4+ answers the
        // client-loaded handshake with a dedicated empty packet. Both are
        // modern-only, therefore Mojang-named at runtime; absence simply
        // means the server has no such contract and there is nothing to send.
        try {
            Class<?> inputClass = bridge.nmsClass("net.minecraft.world.entity.player.Input");
            Class<?> inputPacketClass = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundPlayerInputPacket");
            inputRecordConstructor = inputClass.getConstructor(
                    boolean.class, boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class);
            inputPacketConstructor = inputPacketClass.getConstructor(inputClass);
        } catch (ReflectiveOperationException preKeyboardStream) {
            inputRecordConstructor = null;
            inputPacketConstructor = null;
        }
        try {
            Class<?> loadedClass = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket");
            playerLoadedConstructor = loadedClass.getConstructor();
        } catch (ReflectiveOperationException preLoadedHandshake) {
            playerLoadedConstructor = null;
        }

        // KeepAlive: the server probes the connection's liveness and
        // disconnects a peer that does not echo the id back. A clientless boxer
        // must answer it itself. The pair moved game -> common in 1.20.2; the
        // payload is a single long either era.
        try {
            keepAlivePacketClass = keepAliveClass("ClientboundKeepAlivePacket");
            keepAliveId = singleLongField(keepAlivePacketClass);
            keepAliveResponseConstructor =
                    keepAliveClass("ServerboundKeepAlivePacket").getConstructor(long.class);
        } catch (ReflectiveOperationException noKeepAlive) {
            keepAlivePacketClass = null;
            keepAliveId = null;
            keepAliveResponseConstructor = null;
        }

        // Clientbound shapes.
        motionPacketClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket");
        Class<?> vec3Class = bridge.nmsClass("net.minecraft.world.phys.Vec3");
        vecX = fieldByName(vec3Class, "x");
        vecY = fieldByName(vec3Class, "y");
        vecZ = fieldByName(vec3Class, "z");
        List<Field> ints = new ArrayList<>();
        for (Field field : motionPacketClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (field.getType() == int.class) {
                ints.add(field);
            } else if (field.getType() == vec3Class) {
                motionVec = field;
            }
        }
        if (motionVec != null && ints.size() == 1) {
            motionId = ints.get(0);          // modern: int id + Vec3 movement
        } else if (ints.size() == 4) {
            motionId = ints.get(0);          // legacy: id, xa, ya, za (×8000)
            motionInts = new Field[] {ints.get(1), ints.get(2), ints.get(3)};
        } else {
            throw new NoSuchFieldException("Unrecognized SetEntityMotionPacket layout");
        }

        positionPacketClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
        explodePacketClass = bridge.nmsClass(
                "net.minecraft.network.protocol.game.ClientboundExplodePacket");
        try {
            bundlePacketClass = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ClientboundBundlePacket");
            String subName = bridge.remapMethod(bundlePacketClass, "subPackets");
            bundleSubPackets = Reflect.method(bundlePacketClass, subName);
            if (bundleSubPackets == null) {
                bundleSubPackets = Reflect.method(bundlePacketClass, "subPackets");
            }
        } catch (ClassNotFoundException pre1194) {
            bundlePacketClass = null;
        }

        resolveItemPackets();
    }

    /**
     * Probes the serverbound item-interaction packets — held-slot swap, use-item
     * (rod cast / block raise / potion throw / eat), and the release-use action.
     * Each is independent and best-effort: a shape that fails to resolve leaves
     * its factory null, and the item routines see the capability as absent (they
     * never win arbitration they cannot execute). UseItem/PlayerAction gained a
     * sequence int in 1.19 and UseItem gained yaw/pitch in 1.21.3 — arity-probed.
     */
    private void resolveItemPackets() {
        Class<?> handClass;
        try {
            handClass = bridge.nmsClass("net.minecraft.world.InteractionHand");
            Object[] hands = handClass.getEnumConstants();
            offHand = hands.length > 1 ? hands[1] : hands[0];
        } catch (ReflectiveOperationException noHand) {
            return;
        }

        try {
            Class<?> setCarried = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket");
            setCarriedItemConstructor = setCarried.getConstructor(int.class);
        } catch (ReflectiveOperationException noSetCarried) {
            setCarriedItemConstructor = null;
        }

        try {
            Class<?> useItem = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundUseItemPacket");
            for (Constructor<?> constructor : useItem.getConstructors()) {
                Class<?>[] p = constructor.getParameterTypes();
                if (p.length == 0 || p[0] != handClass) {
                    continue;
                }
                if (p.length == 1) {
                    preferUseItem(constructor, false, false);
                } else if (p.length == 2 && p[1] == int.class) {
                    preferUseItem(constructor, true, false);
                } else if (p.length == 4 && p[1] == int.class
                        && p[2] == float.class && p[3] == float.class) {
                    preferUseItem(constructor, true, true);
                }
            }
            useItemTimestamp = spigotTimestampField(useItem);
        } catch (ReflectiveOperationException noUseItem) {
            useItemConstructor = null;
            useItemTimestamp = null;
        }

        try {
            Class<?> action = bridge.nmsClass(
                    "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket");
            Class<?> actionEnum = nestedEnum(action, "Action");
            releaseUseItemAction = enumByNameContains(actionEnum, "RELEASE_USE_ITEM", 5);
            Class<?> blockPosClass = bridge.nmsClass("net.minecraft.core.BlockPos");
            blockPosOrigin = staticFieldValue(blockPosClass, "ZERO");
            Class<?> directionClass = bridge.nmsClass("net.minecraft.core.Direction");
            directionDown = directionClass.getEnumConstants()[0]; // DOWN is ordinal 0 on every version
            for (Constructor<?> constructor : action.getConstructors()) {
                Class<?>[] p = constructor.getParameterTypes();
                boolean base = p.length >= 3 && p[0] == actionEnum
                        && p[1] == blockPosClass && p[2] == directionClass;
                if (!base) {
                    continue;
                }
                if (p.length == 3) {
                    playerActionConstructor = constructor;
                    playerActionHasSequence = false;
                    break; // prefer the seqless shape when both exist
                }
                if (p.length == 4 && p[3] == int.class) {
                    playerActionConstructor = constructor;
                    playerActionHasSequence = true;
                }
            }
            playerActionTimestamp = spigotTimestampField(action);
        } catch (ReflectiveOperationException noPlayerAction) {
            playerActionConstructor = null;
            playerActionTimestamp = null;
        }
    }

    private void preferUseItem(Constructor<?> constructor, boolean hasSeq, boolean hasRotation) {
        // Prefer the richest shape the server offers (rotation > seq > bare).
        int existing = (useItemHasRotation ? 2 : 0) + (useItemHasSequence ? 1 : 0);
        int candidate = (hasRotation ? 2 : 0) + (hasSeq ? 1 : 0);
        if (useItemConstructor == null || candidate > existing) {
            useItemConstructor = constructor;
            useItemHasSequence = hasSeq;
            useItemHasRotation = hasRotation;
        }
    }

    private static @Nullable Object staticFieldValue(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * The Spigot-patch {@code public long timestamp} anti-spam field on a
     * serverbound packet, made settable — or {@code null} where this build does
     * not carry the patch. A CraftBukkit patch addition, so the name is literal
     * on spigot- and mojang-mapped servers alike (added after remapping).
     */
    private static @Nullable Field spigotTimestampField(@NotNull Class<?> packetClass) {
        try {
            Field field = packetClass.getDeclaredField("timestamp");
            if (field.getType() != long.class || Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * Stamps the wall clock into the packet's Spigot anti-spam field — exactly
     * what the network decode constructor does to every real client's packet.
     * Without it, PlayerConnection.checkLimit sees a never-advancing timestamp,
     * its 300ms reset branch never fires, and after a 9-packet lifetime grace
     * every use-item is silently dropped before the handler runs.
     */
    private static void stampTimestamp(@Nullable Field timestamp, @NotNull Object packet)
            throws ReflectiveOperationException {
        if (timestamp != null) {
            timestamp.setLong(packet, System.currentTimeMillis());
        }
    }

    /** Whether this server exposes the held-slot + use-item + release packets. */
    public boolean itemInteractionsAvailable() {
        return setCarriedItemConstructor != null && useItemConstructor != null
                && playerActionConstructor != null;
    }

    /** {@code ServerboundSetCarriedItemPacket(slot)} — change the selected hotbar slot (0-8). */
    public @Nullable Object setCarriedItem(int slot) throws ReflectiveOperationException {
        return setCarriedItemConstructor == null ? null : setCarriedItemConstructor.newInstance(slot);
    }

    /**
     * {@code ServerboundUseItemPacket} — right-click with the held item (rod cast,
     * block raise, potion throw, eat start). {@code sequence} is the per-boxer
     * block-change counter (ignored pre-1.19); {@code yaw}/{@code pitch} are the
     * current crosshair (ignored pre-1.21.3). Stamped with the same wall clock
     * the decode constructor gives every real client's packet, so Spigot's spam
     * limiter rates the boxer like a socket client instead of a flood.
     */
    public @Nullable Object useItem(boolean mainHand, int sequence, float yaw, float pitch)
            throws ReflectiveOperationException {
        if (useItemConstructor == null) {
            return null;
        }
        Object hand = mainHand ? this.mainHand : offHand;
        Object packet;
        if (useItemHasRotation) {
            packet = useItemConstructor.newInstance(hand, sequence, yaw, pitch);
        } else if (useItemHasSequence) {
            packet = useItemConstructor.newInstance(hand, sequence);
        } else {
            packet = useItemConstructor.newInstance(hand);
        }
        stampTimestamp(useItemTimestamp, packet);
        return packet;
    }

    /**
     * {@code ServerboundPlayerActionPacket(RELEASE_USE_ITEM, …)} — stop using the
     * held item (finish/abort a block or eat). Position and direction are ignored
     * by the handler for this action; origin + DOWN satisfy the constructor. The
     * Spigot timestamp is stamped where the build carries it, matching decode.
     */
    public @Nullable Object releaseUseItem(int sequence) throws ReflectiveOperationException {
        if (playerActionConstructor == null || releaseUseItemAction == null
                || blockPosOrigin == null || directionDown == null) {
            return null;
        }
        Object packet = playerActionHasSequence
                ? playerActionConstructor.newInstance(
                        releaseUseItemAction, blockPosOrigin, directionDown, sequence)
                : playerActionConstructor.newInstance(
                        releaseUseItemAction, blockPosOrigin, directionDown);
        stampTimestamp(playerActionTimestamp, packet);
        return packet;
    }

    /**
     * 1.21.6 compacted the action enum (the shift keys moved to player
     * input), shifting every ordinal — match sprint constants by NAME first
     * (Mojang-mapped on every version where the compaction exists), by
     * remapped name for spigot-mapped servers, and only then by the
     * pre-compaction ordinals 3/4.
     */
    private Object sprintAction(Class<?> actionClass, boolean start) {
        String mojangName = start ? "START_SPRINTING" : "STOP_SPRINTING";
        for (Object constant : actionClass.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(mojangName)) {
                return constant;
            }
        }
        try {
            String remapped = bridge.remapField(actionClass, mojangName);
            for (Object constant : actionClass.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(remapped)) {
                    return constant;
                }
            }
        } catch (Throwable unmapped) {
            // fall through to ordinals
        }
        Object[] constants = actionClass.getEnumConstants();
        return constants[Math.min(start ? 3 : 4, constants.length - 1)];
    }

    private static Object enumByNameContains(Class<?> enumClass, String fragment, int fallbackOrdinal) {
        Object[] constants = enumClass.getEnumConstants();
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().contains(fragment)) {
                return constant;
            }
        }
        return constants[Math.min(fallbackOrdinal, constants.length - 1)];
    }

    private static Class<?> nestedEnum(Class<?> owner, String simpleName) throws ClassNotFoundException {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.isEnum() && nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        // Spigot-mapped nesting keeps the shape even when names shift; an
        // enum nested in a packet with one enum member is unambiguous.
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.isEnum()) {
                return nested;
            }
        }
        throw new ClassNotFoundException(simpleName + " enum in " + owner.getName());
    }

    /** KeepAlive packets live in {@code protocol.common} (1.20.2+) or {@code protocol.game} before. */
    private Class<?> keepAliveClass(String simpleName) throws ClassNotFoundException {
        try {
            return bridge.nmsClass("net.minecraft.network.protocol.common." + simpleName);
        } catch (ClassNotFoundException pre1202) {
            return bridge.nmsClass("net.minecraft.network.protocol.game." + simpleName);
        }
    }

    private static Field singleLongField(Class<?> owner) throws NoSuchFieldException {
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == long.class) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("long id on " + owner.getName());
    }

    private Field fieldByName(Class<?> owner, String mojangName) throws NoSuchFieldException {
        Field field = Reflect.field(owner, bridge.remapField(owner, mojangName));
        if (field == null) {
            field = Reflect.field(owner, mojangName);
        }
        if (field == null) {
            throw new NoSuchFieldException(mojangName + " on " + owner.getName());
        }
        return field;
    }

    private static boolean matches(Class<?>[] parameters, Class<?>... expected) {
        if (parameters.length != expected.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Serverbound factories                                              */
    /* ------------------------------------------------------------------ */

    public @NotNull Object movePosRot(double x, double y, double z, float yaw, float pitch,
            boolean onGround, boolean horizontalCollision) throws ReflectiveOperationException {
        return posRotHasCollisionFlag
                ? posRotConstructor.newInstance(x, y, z, yaw, pitch, onGround, horizontalCollision)
                : posRotConstructor.newInstance(x, y, z, yaw, pitch, onGround);
    }

    public @Nullable Object moveStatusOnly(boolean onGround, boolean horizontalCollision)
            throws ReflectiveOperationException {
        if (statusOnlyConstructor == null) {
            return null;
        }
        return statusOnlyHasCollisionFlag
                ? statusOnlyConstructor.newInstance(onGround, horizontalCollision)
                : statusOnlyConstructor.newInstance(onGround);
    }

    public @NotNull Object acceptTeleport(int teleportId) throws ReflectiveOperationException {
        return acceptTeleportConstructor.newInstance(teleportId);
    }

    public @NotNull Object sprint(@NotNull Object serverPlayer, int entityId, boolean start)
            throws ReflectiveOperationException {
        Object action = start ? startSprinting : stopSprinting;
        Object subject = playerCommandTakesEntity ? serverPlayer : entityId;
        return playerCommandHasData
                ? playerCommandConstructor.newInstance(subject, action, 0)
                : playerCommandConstructor.newInstance(subject, action);
    }

    /** Attack packet against a live Bukkit entity (resolves its NMS handle). */
    public @NotNull Object attack(@NotNull Entity target) throws ReflectiveOperationException {
        if (attackFactory != null) {
            Object handle = bridge.handleOf(target);
            return attackFactory.invoke(null, handle, false);
        }
        return attackConstructor.newInstance(target.getEntityId());
    }

    public @NotNull Object swing() throws ReflectiveOperationException {
        return swingConstructor.newInstance(mainHand);
    }

    /**
     * The answer to a {@code ClientboundKeepAlivePacket}: echo the id back so
     * the server's liveness check passes. {@code null} where the packet pair is
     * absent (it never is on a supported server).
     */
    public @Nullable Object keepAliveResponse(long id) throws ReflectiveOperationException {
        return keepAliveResponseConstructor == null
                ? null : keepAliveResponseConstructor.newInstance(id);
    }

    public @NotNull Object respawn() throws ReflectiveOperationException {
        return clientCommandConstructor.newInstance(performRespawn);
    }

    /**
     * The whole-keyboard state (1.21.2+), the packet a real client sends
     * whenever its held keys change. The server fires
     * {@code PlayerInputEvent} from it, derives sneak from {@code shift},
     * and steers ridden vehicles by it. {@code null} below 1.21.2 — that
     * era's input packet is the vehicle-steering form a walking client
     * never sends.
     */
    public @Nullable Object playerInput(boolean forward, boolean backward, boolean left,
            boolean right, boolean jump, boolean shift, boolean sprint)
            throws ReflectiveOperationException {
        if (inputPacketConstructor == null || inputRecordConstructor == null) {
            return null;
        }
        return inputPacketConstructor.newInstance(inputRecordConstructor.newInstance(
                forward, backward, left, right, jump, shift, sprint));
    }

    /**
     * The client-loaded handshake (1.21.4+). The server arms a 60-tick gate
     * at every ServerGamePacketListener construction and again on every
     * respawn; until the client answers (or the gate times out), sprint
     * commands, interactions and movement are silently dropped. A real
     * client answers the moment its level renders. {@code null} where the
     * server has no such gate.
     */
    public @Nullable Object playerLoaded() throws ReflectiveOperationException {
        return playerLoadedConstructor == null ? null : playerLoadedConstructor.newInstance();
    }

    /* ------------------------------------------------------------------ */
    /*  Dispatch                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Hands the packet to the boxer's game listener exactly as the vanilla
     * packet_handler does after decode — same handlers, same validation,
     * same events. Must run on the thread owning the boxer (the handlers'
     * ensureRunningOnSameThread executes inline there).
     */
    public void dispatch(@NotNull Object packet, @NotNull Object gameListener) throws ReflectiveOperationException {
        Method handle = handleMethods.get(packet.getClass());
        if (handle == null) {
            for (Method method : packet.getClass().getMethods()) {
                if (method.getParameterCount() == 1
                        && method.getReturnType() == void.class
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes()[0].isInterface()
                        && method.getParameterTypes()[0].isInstance(gameListener)) {
                    method.setAccessible(true);
                    handleMethods.put(packet.getClass(), handle = method);
                    break;
                }
            }
            if (handle == null) {
                throw new NoSuchMethodException("handle() not found on " + packet.getClass());
            }
        }
        handle.invoke(packet, gameListener);
    }

    /* ------------------------------------------------------------------ */
    /*  Clientbound recognition                                            */
    /* ------------------------------------------------------------------ */

    /** Decodes the packets the brain reacts to; unwraps 1.19.4+ bundles. */
    public @NotNull List<Inbound> recognize(@NotNull Object packet) {
        List<Inbound> decoded = new ArrayList<>(1);
        collect(packet, decoded, 0);
        return decoded;
    }

    private void collect(Object packet, List<Inbound> into, int depth) {
        if (depth > 2) {
            return;
        }
        try {
            if (bundlePacketClass != null && bundlePacketClass.isInstance(packet)
                    && bundleSubPackets != null) {
                Object subPackets = bundleSubPackets.invoke(packet);
                if (subPackets instanceof Iterable<?> iterable) {
                    for (Object sub : iterable) {
                        collect(sub, into, depth + 1);
                    }
                }
                return;
            }
            if (keepAlivePacketClass != null && keepAlivePacketClass.isInstance(packet)) {
                into.add(new Inbound.KeepAlive(keepAliveId.getLong(packet)));
            } else if (motionPacketClass.isInstance(packet)) {
                into.add(decodeVelocity(packet));
            } else if (positionPacketClass.isInstance(packet)) {
                Inbound.PositionSync sync = decodePosition(packet);
                if (sync != null) {
                    into.add(sync);
                }
            } else if (explodePacketClass.isInstance(packet)) {
                Inbound.Explosion explosion = decodeExplosion(packet);
                if (explosion != null) {
                    into.add(explosion);
                }
            }
        } catch (Throwable decodeFailure) {
            // A packet the brain cannot decode is a packet it ignores — the
            // server's authoritative resync (teleports) self-heals drift.
        }
    }

    private Inbound.Velocity decodeVelocity(Object packet) throws ReflectiveOperationException {
        int id = motionId.getInt(packet);
        if (motionVec != null) {
            Object vec = motionVec.get(packet);
            return new Inbound.Velocity(id,
                    vecX.getDouble(vec), vecY.getDouble(vec), vecZ.getDouble(vec));
        }
        return new Inbound.Velocity(id,
                motionInts[0].getInt(packet) / 8000.0,
                motionInts[1].getInt(packet) / 8000.0,
                motionInts[2].getInt(packet) / 8000.0);
    }

    private @Nullable Inbound.PositionSync decodePosition(Object packet) throws ReflectiveOperationException {
        Integer id = null;
        Double x = null;
        Double y = null;
        Double z = null;
        Float yaw = null;
        Float pitch = null;
        Set<?> relatives = null;
        Object change = null;

        for (Field field : packet.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == double.class) {
                double value = field.getDouble(packet);
                if (x == null) {
                    x = value;
                } else if (y == null) {
                    y = value;
                } else if (z == null) {
                    z = value;
                }
            } else if (type == float.class) {
                float value = field.getFloat(packet);
                if (yaw == null) {
                    yaw = value;
                } else if (pitch == null) {
                    pitch = value;
                }
            } else if (type == int.class) {
                id = field.getInt(packet);
            } else if (Set.class.isAssignableFrom(type)) {
                relatives = (Set<?>) field.get(packet);
            } else if (type.getSimpleName().equals("PositionMoveRotation")) {
                change = field.get(packet);
            }
        }

        boolean[] flags = relativeFlags(relatives);
        if (change != null) {
            // 1.21.2+: PositionMoveRotation(position, deltaMovement, yRot, xRot)
            // — modern-only, therefore Mojang-named at runtime.
            Object position = change.getClass().getMethod("position").invoke(change);
            Object delta = change.getClass().getMethod("deltaMovement").invoke(change);
            float yRot = (float) change.getClass().getMethod("yRot").invoke(change);
            float xRot = (float) change.getClass().getMethod("xRot").invoke(change);
            Inbound.PositionSync.Motion motion = new Inbound.PositionSync.Motion(
                    vecX.getDouble(delta), vecY.getDouble(delta), vecZ.getDouble(delta),
                    flags[5], flags[6], flags[7]);
            return new Inbound.PositionSync(id == null ? -1 : id,
                    vecX.getDouble(position), vecY.getDouble(position), vecZ.getDouble(position),
                    yRot, xRot, flags[0], flags[1], flags[2], flags[3], flags[4], motion);
        }
        if (x == null || y == null || z == null || yaw == null || pitch == null || id == null) {
            return null;
        }
        return new Inbound.PositionSync(id, x, y, z, yaw, pitch,
                flags[0], flags[1], flags[2], flags[3], flags[4], null);
    }

    /**
     * Relative-teleport flags by ORDINAL — X, Y, Z, Y_ROT, X_ROT are 0–4 in
     * every version's enum, the modern delta flags 5–7; constant names are
     * obfuscated on spigot-mapped servers but ordinals never moved.
     */
    private static boolean[] relativeFlags(@Nullable Set<?> relatives) {
        boolean[] flags = new boolean[9];
        if (relatives != null) {
            for (Object relative : relatives) {
                if (relative instanceof Enum<?> constant && constant.ordinal() < flags.length) {
                    flags[constant.ordinal()] = true;
                }
            }
        }
        return flags;
    }

    private @Nullable Inbound.Explosion decodeExplosion(Object packet) throws ReflectiveOperationException {
        // Legacy (≤1.21.1): float knockback components behind getters.
        Method getX = Reflect.method(packet.getClass(),
                bridge.remapMethod(packet.getClass(), "getKnockbackX"));
        if (getX == null) {
            getX = Reflect.method(packet.getClass(), "getKnockbackX");
        }
        if (getX != null) {
            Method getY = Reflect.method(packet.getClass(),
                    bridge.remapMethod(packet.getClass(), "getKnockbackY"));
            if (getY == null) {
                getY = Reflect.method(packet.getClass(), "getKnockbackY");
            }
            Method getZ = Reflect.method(packet.getClass(),
                    bridge.remapMethod(packet.getClass(), "getKnockbackZ"));
            if (getZ == null) {
                getZ = Reflect.method(packet.getClass(), "getKnockbackZ");
            }
            if (getY != null && getZ != null) {
                return new Inbound.Explosion(
                        ((Number) getX.invoke(packet)).doubleValue(),
                        ((Number) getY.invoke(packet)).doubleValue(),
                        ((Number) getZ.invoke(packet)).doubleValue());
            }
        }
        // Modern (1.21.2+): Optional<Vec3> playerKnockback — Mojang-named.
        for (Field field : packet.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != Optional.class) {
                continue;
            }
            field.setAccessible(true);
            Optional<?> knockback = (Optional<?>) field.get(packet);
            if (knockback.isEmpty()) {
                return null;
            }
            Object vec = knockback.get();
            if (vecX.getDeclaringClass().isInstance(vec)) {
                return new Inbound.Explosion(
                        vecX.getDouble(vec), vecY.getDouble(vec), vecZ.getDouble(vec));
            }
        }
        return null;
    }
}

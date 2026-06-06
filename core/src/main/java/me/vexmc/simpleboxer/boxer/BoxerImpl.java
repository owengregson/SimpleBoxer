package me.vexmc.simpleboxer.boxer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.aim.AimSpring;
import me.vexmc.simpleboxer.common.combat.ClickScheduler;
import me.vexmc.simpleboxer.common.latency.LatencyLine;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.nms.CapturedPacket;
import me.vexmc.simpleboxer.nms.Inbound;
import me.vexmc.simpleboxer.nms.NmsBridge;
import me.vexmc.simpleboxer.nms.PacketIO;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One live boxer: the ServerPlayer handle, the client emulator, and the two
 * latency lines between them. Everything here runs on the boxer's owning
 * thread except {@code outbound}, which the capture handler feeds from
 * whatever thread the server writes on.
 *
 * <p>The loop each tick: drain matured clientbound packets into the client
 * state (velocity REPLACES motion, teleports confirm + snap, explosions
 * ADD) → decide input from the perceived world → step the physics → queue
 * the resulting movement through the action line → dispatch matured actions
 * through the boxer's own game listener → tick the ServerPlayer.</p>
 */
final class BoxerImpl implements Boxer {

    private final String name;
    private final UUID uuid;
    private final BoxerManager manager;
    private final NmsBridge bridge;
    private final PacketIO packetIO;
    private final Logger logger;
    private final NmsBridge.SpawnedPlayer spawned;
    private final BukkitCollisionView collisionView;
    private final ClientPhysics physics;
    private final AimSpring aim;

    /** Clientbound packets, fed cross-thread by the capture handler. */
    private final LatencyLine<CapturedPacket> outbound = new LatencyLine<>();
    /** Decided actions on their way to the server (the other half of RTT). */
    private final LatencyLine<Action> actions = new LatencyLine<>();
    /** World snapshots aging into the brain's delayed view of its target. */
    private final LatencyLine<TargetView> perception = new LatencyLine<>();

    private final ClickScheduler clicker;

    private volatile BoxerSettings settings;
    private volatile @Nullable Player target;
    private volatile boolean paused;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    /** What the boxer believes about its target — one perception-delay old. */
    private record TargetView(double x, double y, double z, double eyeY,
            @NotNull Player entity) {}

    private @Nullable TargetView perceived;

    /* W-tap state machine: countdown to release, then released ticks. */
    private int wtapCountdown = -1;
    private int wtapReleaseLeft;

    /* Strafe state: current direction and ticks until the next flip. */
    private double strafeSign = 1.0;
    private int strafeFlipIn;

    private double lastSentX;
    private double lastSentY;
    private double lastSentZ;
    private float lastSentYaw;
    private float lastSentPitch;
    private int idleMoveTicks;

    private sealed interface Action {
        record Move(double x, double y, double z, float yaw, float pitch,
                boolean onGround, boolean horizontalCollision, boolean moved, boolean rotated)
                implements Action {}

        record Sprint(boolean start) implements Action {}

        record AcceptTeleport(int id) implements Action {}

        record Attack(@NotNull Player victim) implements Action {}

        record Swing() implements Action {}
    }

    private boolean serverSprinting;

    BoxerImpl(
            @NotNull String name,
            @NotNull UUID uuid,
            @NotNull BoxerSettings settings,
            @NotNull BoxerManager manager,
            @NotNull NmsBridge bridge,
            @NotNull PacketIO packetIO,
            @NotNull NmsBridge.SpawnedPlayer spawned,
            @NotNull Location spawnLocation,
            @NotNull Logger logger) {
        this.name = name;
        this.uuid = uuid;
        this.settings = settings;
        this.manager = manager;
        this.bridge = bridge;
        this.packetIO = packetIO;
        this.spawned = spawned;
        this.logger = logger;
        this.collisionView = new BukkitCollisionView(spawnLocation.getWorld());
        this.physics = new ClientPhysics(
                spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
        this.aim = new AimSpring(settings.aim(), spawnLocation.getYaw(), spawnLocation.getPitch());
        // Seeded by identity: identical boxers click identically — a
        // deterministic fixture is a debuggable fixture.
        this.clicker = new ClickScheduler(settings.cps(), settings.clickJitter(),
                uuid.getLeastSignificantBits());
        this.lastSentX = spawnLocation.getX();
        this.lastSentY = spawnLocation.getY();
        this.lastSentZ = spawnLocation.getZ();
        this.lastSentYaw = spawnLocation.getYaw();
        this.lastSentPitch = spawnLocation.getPitch();
    }

    /** The capture sink — ANY thread; enqueue only. */
    void onOutboundPacket(@NotNull CapturedPacket packet) {
        outbound.offer(packet, packet.nanos());
    }

    /* ------------------------------------------------------------------ */
    /*  The brain tick (owning thread)                                     */
    /* ------------------------------------------------------------------ */

    void tick() {
        if (removed.get()) {
            return;
        }
        long now = System.nanoTime();
        long oneWay = TimeUnit.MILLISECONDS.toNanos(settings.pingMs()) / 2;

        // 1. Perceive: matured clientbound packets become client state, and
        //    a fresh world snapshot starts aging toward the brain.
        for (CapturedPacket captured : outbound.drain(now, oneWay)) {
            for (Inbound inbound : packetIO.recognize(captured.packet())) {
                route(inbound, now);
            }
        }
        snapshotTarget(now);
        TargetView matured = perception.drainLatest(now, oneWay);
        if (matured != null) {
            perceived = matured;
        }
        if (perceived != null && !perceived.entity().isOnline()) {
            perceived = null;
        }

        // 2. Decide + integrate, unless paused (a paused client still
        //    receives packets — knockback flies you around while AFK too).
        MoveInput input = paused ? MoveInput.IDLE : decideInput();
        if (!paused) {
            considerClicking(now);
        }
        physics.step(input, aim.yaw(), collisionView);

        // 3. Report movement the way a real client does.
        queueMovement(now);

        // 4. Matured actions reach the server.
        for (Action action : actions.drain(now, oneWay)) {
            dispatch(action);
        }

        // 5. Tick the ServerPlayer (timers, effects, food — the server
        //    half of being a player).
        bridge.tick(spawned.serverPlayer());
    }

    private void snapshotTarget(long now) {
        Player currentTarget = target;
        if (currentTarget == null || !currentTarget.isOnline()
                || currentTarget.getWorld() != spawned.player().getWorld()) {
            perceived = null;
            perception.clear();
            return;
        }
        Location location = currentTarget.getLocation();
        perception.offer(new TargetView(location.getX(), location.getY(), location.getZ(),
                location.getY() + currentTarget.getEyeHeight(), currentTarget), now);
    }

    private void route(@NotNull Inbound inbound, long now) {
        if (inbound instanceof Inbound.Velocity velocity) {
            if (velocity.entityId() == spawned.entityId()) {
                physics.applyVelocity(velocity.vx(), velocity.vy(), velocity.vz());
            }
            return;
        }
        if (inbound instanceof Inbound.Explosion explosion) {
            physics.addVelocity(explosion.kx(), explosion.ky(), explosion.kz());
            return;
        }
        if (inbound instanceof Inbound.PositionSync sync) {
            double x = sync.relativeX() ? physics.x() + sync.x() : sync.x();
            double y = sync.relativeY() ? physics.y() + sync.y() : sync.y();
            double z = sync.relativeZ() ? physics.z() + sync.z() : sync.z();
            var velocity = physics.velocity();
            physics.teleport(x, y, z);
            if (sync.velocity() != null) {
                Inbound.PositionSync.Motion motion = sync.velocity();
                physics.applyVelocity(
                        motion.deltaX() ? velocity.x() + motion.x() : motion.x(),
                        motion.deltaY() ? velocity.y() + motion.y() : motion.y(),
                        motion.deltaZ() ? velocity.z() + motion.z() : motion.z());
            } else {
                // Legacy semantics: an absolute teleport axis zeroes that
                // axis' client motion (why /tp stops momentum).
                physics.applyVelocity(
                        sync.relativeX() ? velocity.x() : 0.0,
                        sync.relativeY() ? velocity.y() : 0.0,
                        sync.relativeZ() ? velocity.z() : 0.0);
            }
            float yaw = sync.relativeYaw() ? aim.yaw() + sync.yaw() : sync.yaw();
            float pitch = sync.relativePitch() ? aim.pitch() + sync.pitch() : sync.pitch();
            aim.snapTo(yaw, pitch);
            actions.offer(new Action.AcceptTeleport(sync.teleportId()), now);
        }
    }

    /**
     * The decision frame, working entirely from the DELAYED target view —
     * a 100 ms boxer chases where its target was 50 ms ago, aims there,
     * judges reach there, exactly like the laggy client it emulates.
     */
    private @NotNull MoveInput decideInput() {
        TargetView view = perceived;
        if (view == null) {
            syncSprint(false);
            return MoveInput.IDLE;
        }
        double dx = view.x() - physics.x();
        double dz = view.z() - physics.z();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Eyes on the perceived chest: yaw from the horizontal bearing,
        // pitch from eye height to a point just below the target's eyes.
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double eyeDy = (view.eyeY() - 0.4) - (physics.y() + 1.62);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(eyeDy, Math.max(distance, 1.0E-4)));
        aim.step(desiredYaw, desiredPitch);

        BoxerSettings current = settings;
        BoxerSettings.Movement movement = current.movement();
        if (movement.style() == BoxerSettings.Movement.Style.STAND) {
            syncSprint(false);
            return MoveInput.IDLE;
        }

        // W-tap: the countdown opened by a landed hit expires into a
        // released-forward window; sprint drops with it and re-arms on the
        // re-press — the exact packet rhythm of a human w-tapper.
        if (wtapCountdown > 0) {
            wtapCountdown--;
        } else if (wtapCountdown == 0) {
            wtapCountdown = -1;
            wtapReleaseLeft = current.wtap().releaseTicks();
        }
        if (wtapReleaseLeft > 0) {
            wtapReleaseLeft--;
            syncSprint(false);
            return MoveInput.IDLE;
        }

        double strafe = strafeInput(movement, distance);
        boolean inRange = distance <= movement.stopDistance();
        double forward;
        if (inRange) {
            // Range discipline: hold the pocket, back out when overlapped.
            forward = distance < movement.stopDistance() - 0.8 ? -0.4 : 0.0;
        } else {
            forward = 1.0;
        }

        boolean sprinting = movement.sprint() && forward > 0.9;
        syncSprint(sprinting);
        // Jump single-block steps the collision could not solve.
        boolean jump = physics.onGround() && physics.horizontalCollision();
        return new MoveInput(forward, strafe, jump, sprinting, false);
    }

    private double strafeInput(BoxerSettings.Movement movement, double distance) {
        boolean close = distance <= movement.stopDistance() + 1.5;
        switch (movement.style()) {
            case STRAFE_CIRCLE -> {
                if (!close) {
                    return 0.0;
                }
                // Orbit; flip when the wall interrupts or on a slow cadence.
                if (--strafeFlipIn <= 0 || physics.horizontalCollision()) {
                    strafeFlipIn = 40 + (int) (Math.abs(uuid.getMostSignificantBits()) % 25L);
                    strafeSign = -strafeSign;
                }
                return strafeSign;
            }
            case STRAFE_WEAVE -> {
                // Weave on approach and in the pocket alike.
                if (--strafeFlipIn <= 0) {
                    strafeFlipIn = 8 + (int) (Math.abs(uuid.getMostSignificantBits() >> 8) % 7L);
                    strafeSign = -strafeSign;
                }
                return strafeSign * 0.7;
            }
            default -> {
                return 0.0;
            }
        }
    }

    /**
     * The clicking finger: CPS-clocked; a click always swings, and lands an
     * attack only when the perceived target sits inside reach AND inside the
     * aim cone — out-of-range spam swings at air like any real spammer.
     * Attack dispatches BEFORE swing: the real client order in every era
     * (a swing-first bot resets the attack meter on modern servers).
     */
    private void considerClicking(long nowNanos) {
        TargetView view = perceived;
        if (!clicker.shouldClick(nowNanos / 1_000_000L)) {
            return;
        }
        long now = System.nanoTime();
        if (view != null) {
            double dx = view.x() - physics.x();
            double dy = (view.y() + 0.9) - (physics.y() + 1.62);
            double dz = view.z() - physics.z();
            double reachSq = settings.reach() * settings.reach();
            boolean inReach = dx * dx + dy * dy + dz * dz <= reachSq;
            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            boolean aimed = aim.yawErrorTo(desiredYaw) <= settings.aimToleranceDegrees();
            if (inReach && aimed) {
                actions.offer(new Action.Attack(view.entity()), now);
            }
        }
        actions.offer(new Action.Swing(), now);
    }

    /** A hit this boxer threw landed (server confirmation, owning thread). */
    void onHitLanded() {
        BoxerSettings.WTap wtap = settings.wtap();
        if (wtap.enabled() && wtapReleaseLeft == 0 && wtapCountdown < 0) {
            wtapCountdown = wtap.delayTicks();
        }
    }

    /** Sprint state changes ship as the real PlayerCommand packets. */
    private void syncSprint(boolean sprinting) {
        if (serverSprinting == sprinting) {
            return;
        }
        serverSprinting = sprinting;
        actions.offer(new Action.Sprint(sprinting), System.nanoTime());
    }

    private void queueMovement(long now) {
        double x = physics.x();
        double y = physics.y();
        double z = physics.z();
        float yaw = aim.yaw();
        float pitch = aim.pitch();
        boolean moved = Math.abs(x - lastSentX) > 2.0E-4
                || Math.abs(y - lastSentY) > 2.0E-4
                || Math.abs(z - lastSentZ) > 2.0E-4;
        boolean rotated = Math.abs(yaw - lastSentYaw) > 1.0E-3
                || Math.abs(pitch - lastSentPitch) > 1.0E-3;
        if (moved || rotated || ++idleMoveTicks >= 20) {
            idleMoveTicks = 0;
            actions.offer(new Action.Move(x, y, z, yaw, pitch,
                    physics.onGround(), physics.horizontalCollision(), moved, rotated), now);
            lastSentX = x;
            lastSentY = y;
            lastSentZ = z;
            lastSentYaw = yaw;
            lastSentPitch = pitch;
        }
    }

    private void dispatch(@NotNull Action action) {
        try {
            if (action instanceof Action.Move move) {
                Object packet = move.moved() || move.rotated()
                        ? packetIO.movePosRot(move.x(), move.y(), move.z(), move.yaw(),
                                move.pitch(), move.onGround(), move.horizontalCollision())
                        : packetIO.moveStatusOnly(move.onGround(), move.horizontalCollision());
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                }
            } else if (action instanceof Action.Sprint sprint) {
                packetIO.dispatch(
                        packetIO.sprint(spawned.serverPlayer(), spawned.entityId(), sprint.start()),
                        spawned.gameListener());
            } else if (action instanceof Action.AcceptTeleport accept) {
                packetIO.dispatch(packetIO.acceptTeleport(accept.id()), spawned.gameListener());
            } else if (action instanceof Action.Attack attack) {
                if (attack.victim().isOnline()) {
                    packetIO.dispatch(packetIO.attack(attack.victim()), spawned.gameListener());
                }
            } else if (action instanceof Action.Swing) {
                packetIO.dispatch(packetIO.swing(), spawned.gameListener());
            }
        } catch (Throwable failure) {
            logger.warning("[" + name + "] action dispatch failed: " + failure);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  API                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull UUID uuid() {
        return uuid;
    }

    @Override
    public @NotNull Player player() {
        return spawned.player();
    }

    @Override
    public @NotNull BoxerSettings settings() {
        return settings;
    }

    @Override
    public void retune(@NotNull BoxerSettings newSettings) {
        this.settings = newSettings;
        this.aim.retune(newSettings.aim());
        this.clicker.retune(newSettings.cps(), newSettings.clickJitter());
    }

    @Override
    public @NotNull Optional<Player> target() {
        return Optional.ofNullable(target);
    }

    @Override
    public void setTarget(@Nullable Player newTarget) {
        this.target = newTarget;
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public void pause() {
        this.paused = true;
    }

    @Override
    public void resume() {
        this.paused = false;
    }

    @Override
    public void remove() {
        manager.remove(this);
    }

    /** Manager-internal: the actual despawn, on the owning thread. */
    void despawn(@NotNull String quitMessage) {
        if (removed.compareAndSet(false, true)) {
            bridge.remove(spawned, quitMessage);
            outbound.clear();
            actions.clear();
        }
    }

    boolean isRemoved() {
        return removed.get();
    }

    @NotNull NmsBridge.SpawnedPlayer spawned() {
        return spawned;
    }
}

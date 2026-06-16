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
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.nms.CapturedPacket;
import me.vexmc.simpleboxer.nms.Inbound;
import me.vexmc.simpleboxer.nms.NmsBridge;
import me.vexmc.simpleboxer.nms.PacketIO;
import org.bukkit.GameMode;
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

    /** -Dsimpleboxer.debug=true: per-event brain tracing (matrix forensics). */
    private static final boolean DEBUG = Boolean.getBoolean("simpleboxer.debug");

    private final String name;
    private final UUID uuid;
    private final BoxerManager manager;
    private final NmsBridge bridge;
    private final PacketIO packetIO;
    private final Logger logger;
    private NmsBridge.SpawnedPlayer spawned;
    private final BukkitCollisionView collisionView;
    private final ClientPhysics physics;
    private final AimSpring aim;
    /** True where the server entity-ticks the boxer for us (Folia regions). */
    private final boolean serverTicksEntity;
    /**
     * True where knockback is captured from Paper's EntityKnockbackEvent (full,
     * at apply time) instead of polled from {@code hurtMarked} after the tick.
     * The poll races the region's own doTick on Folia and loses its horizontal
     * component to ground friction — the boxer "pops up" with no push.
     */
    private final boolean eventBasedKnockback;

    /** Clientbound packets, fed cross-thread by the capture handler. */
    private final LatencyLine<CapturedPacket> outbound = new LatencyLine<>();
    /** Decided actions on their way to the server (the other half of RTT). */
    private final LatencyLine<Action> actions = new LatencyLine<>();
    /** World snapshots aging into the brain's delayed view of its target. */
    private final LatencyLine<TargetView> perception = new LatencyLine<>();
    /** Own attributes/effects aging in like UpdateAttributes packets would. */
    private final LatencyLine<PlayerTraits.Traits> traits = new LatencyLine<>();
    /** Server-applied knockback (EntityKnockbackEvent), aged like a velocity packet. */
    private final LatencyLine<Vec3d> receivedKnockback = new LatencyLine<>();

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

    /* Consecutive ticks the target has stood above an auto-step. A single jump
     * keeps an opponent ~8 ticks above us; only a sustained rise is terrain. */
    private int climbTicks;
    private static final int CLIMB_SUSTAIN_TICKS = 10;

    private double lastSentX;
    private double lastSentY;
    private double lastSentZ;
    private float lastSentYaw;
    private float lastSentPitch;
    private int idleMoveTicks;

    /**
     * Where our packets last left the boxer's server position. On a server that
     * entity-ticks the boxer for us, the region's own doTick travels the body by
     * gravity/knockback between our ticks; we re-anchor to this each tick so the
     * server position follows ONLY our move packets, as a real client's does.
     * A change larger than {@link #TELEPORT_RESYNC_SQ} is a real reposition
     * (teleport, respawn) and is adopted instead of undone.
     */
    private boolean hasAnchor;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    /** Squared distance past which a server-position change is a teleport, not doTick drift. */
    private static final double TELEPORT_RESYNC_SQ = 16.0;

    private sealed interface Action {
        record Move(double x, double y, double z, float yaw, float pitch,
                boolean onGround, boolean horizontalCollision, boolean moved, boolean rotated)
                implements Action {}

        record Sprint(boolean start) implements Action {}

        record Input(boolean forward, boolean backward, boolean left, boolean right,
                boolean jump, boolean shift, boolean sprint) implements Action {}

        record AcceptTeleport(int id) implements Action {}

        record Attack(@NotNull Player victim) implements Action {}

        record Swing() implements Action {}

        record KeepAlive(long id) implements Action {}
    }

    private boolean serverSprinting;
    /** Sprint PlayerCommands queued on the action line but not yet shipped. */
    private int sprintActionsInFlight;
    /** The keyboard state last queued for the wire — input ships on change. */
    private @Nullable Action.Input lastQueuedInput;

    BoxerImpl(
            @NotNull String name,
            @NotNull UUID uuid,
            @NotNull BoxerSettings settings,
            @NotNull BoxerManager manager,
            @NotNull NmsBridge bridge,
            @NotNull PacketIO packetIO,
            @NotNull NmsBridge.SpawnedPlayer spawned,
            @NotNull Location spawnLocation,
            boolean serverTicksEntity,
            boolean eventBasedKnockback,
            @NotNull Logger logger) {
        this.name = name;
        this.uuid = uuid;
        this.settings = settings;
        this.manager = manager;
        this.bridge = bridge;
        this.packetIO = packetIO;
        this.spawned = spawned;
        this.serverTicksEntity = serverTicksEntity;
        this.eventBasedKnockback = eventBasedKnockback;
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

    /**
     * The boxer's owning thread, from {@code EntityKnockbackEvent}: the full
     * post-knockback velocity the server computed, before its own tick can decay
     * it. Aged through the perception line and applied like a velocity packet.
     */
    void onKnockback(double vx, double vy, double vz) {
        receivedKnockback.offer(new Vec3d(vx, vy, vz), System.nanoTime());
    }

    /* ------------------------------------------------------------------ */
    /*  The brain tick (owning thread)                                     */
    /* ------------------------------------------------------------------ */

    void tick() {
        if (removed.get()) {
            return;
        }
        refreshHandleAfterRespawn();
        // Where the server entity-ticks the boxer for us, the region's own
        // doTick has travelled the body (gravity, residual knockback) since our
        // last tick. Undo that drift up front so the server position follows
        // ONLY our move packets — but adopt a large change, which is a real
        // server-driven reposition (a teleport or respawn), not tick drift.
        if (serverTicksEntity && hasAnchor) {
            reanchorServerPosition();
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
        // Knockback the server applied to us, captured at full strength from
        // EntityKnockbackEvent and aged like the velocity packet a real client
        // would receive. REPLACES motion, exactly as a velocity packet does.
        for (Vec3d knockback : receivedKnockback.drain(now, oneWay)) {
            physics.applyVelocity(knockback.x(), knockback.y(), knockback.z());
        }
        snapshotTarget(now);
        TargetView matured = perception.drainLatest(now, oneWay);
        if (matured != null) {
            perceived = matured;
        }
        if (perceived != null && !perceived.entity().isOnline()) {
            perceived = null;
        }
        // Self-knowledge rides the same delayed wire: Speed/Slowness (the
        // movement-speed attribute, from any source) and Jump Boost reach a
        // real client as packets, so they reach the integrator through the
        // perception delay too.
        traits.offer(PlayerTraits.read(spawned.player()), now);
        PlayerTraits.Traits knownTraits = traits.drainLatest(now, oneWay);
        if (knownTraits != null) {
            physics.setWalkSpeed(knownTraits.walkSpeed());
            physics.setJumpBoostAmplifier(knownTraits.jumpBoostAmplifier());
        }

        // 2. Decide + integrate, unless paused (a paused client still
        //    receives packets — knockback flies you around while AFK too).
        MoveInput input = paused ? MoveInput.IDLE : decideInput();
        queueInput(input, now);
        if (!paused) {
            considerClicking(now);
        }
        physics.step(input, aim.yaw(), collisionView);
        // pushEntities, the client-predicted half: vanilla shoves the local
        // player away from every overlapping body AFTER travel, the delta
        // riding into the next tick's move. Paused boxers get shoved too —
        // an AFK body is still a body.
        pushAwayFromNeighbors();

        // 3. Report movement the way a real client does.
        queueMovement(now);

        // 4. Matured actions reach the server.
        for (Action action : actions.drain(now, oneWay)) {
            dispatch(action);
        }

        // 5. Ship the boxer's own pending knockback BEFORE the tick: doTick's
        //    travel drags the server motion fields, and a packet synthesized
        //    after it would carry a once-decayed stamp. Skipped where knockback
        //    is captured from EntityKnockbackEvent instead (it can't win this
        //    poll against the region's own tick), and there the live tracker is
        //    left to broadcast the velocity to viewers itself.
        if (!eventBasedKnockback) {
            Object selfVelocity = bridge.drainHurtMarked(spawned.serverPlayer(), spawned.player());
            if (selfVelocity != null) {
                onOutboundPacket(new CapturedPacket(System.nanoTime(), selfVelocity));
            }
        }

        // 6. Tick the ServerPlayer (timers, effects, food — the server half of
        //    being a player), but ONLY where the server will not do it itself.
        //    doTick also runs the entity's own travel, displacing the boxer by
        //    its server motion fields — real players never show this because
        //    their clients stream absolute positions every tick that overwrite
        //    it. The boxer's server position must follow ONLY its move packets,
        //    so any doTick displacement is undone.
        if (serverTicksEntity) {
            // The owning region already ticked (or will tick) this entity this
            // server tick. A second doTick here would double every timer (attack
            // cooldown, effects, food) and re-travel the body. Just record where
            // our move packets left the server position; next tick re-anchors to
            // it, undoing the region's intervening travel.
            Location anchor = spawned.player().getLocation();
            anchorX = anchor.getX();
            anchorY = anchor.getY();
            anchorZ = anchor.getZ();
            hasAnchor = true;
        } else {
            Location preTick = spawned.player().getLocation();
            bridge.tick(spawned.serverPlayer());
            Location postTick = spawned.player().getLocation();
            if (postTick.getX() != preTick.getX() || postTick.getY() != preTick.getY()
                    || postTick.getZ() != preTick.getZ()) {
                try {
                    bridge.setPosition(spawned.serverPlayer(),
                            preTick.getX(), preTick.getY(), preTick.getZ());
                } catch (ReflectiveOperationException failure) {
                    logger.warning("[" + name + "] position restore failed: " + failure);
                }
            }
        }
    }

    /**
     * Undo the owning region's between-tick doTick travel by snapping the server
     * position back to where our packets last left it — unless it has moved
     * farther than a teleport threshold, which means the server itself
     * repositioned the boxer (a teleport or respawn) and we adopt that instead.
     */
    private void reanchorServerPosition() {
        Location current = spawned.player().getLocation();
        double dx = current.getX() - anchorX;
        double dy = current.getY() - anchorY;
        double dz = current.getZ() - anchorZ;
        if (dx * dx + dy * dy + dz * dz > TELEPORT_RESYNC_SQ) {
            return; // a real server reposition — let it stand; the new anchor is recorded this tick
        }
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return;
        }
        try {
            bridge.setPosition(spawned.serverPlayer(), anchorX, anchorY, anchorZ);
        } catch (ReflectiveOperationException failure) {
            logger.warning("[" + name + "] position re-anchor failed: " + failure);
        }
    }

    /**
     * Respawn REPLACES the ServerPlayer entity (and its entity id) while the
     * Bukkit player and the connection survive — stale handles would tick a
     * detached corpse and filter velocity packets by a dead id. The Connection
     * (and our capture handler on its channel) carries over untouched.
     */
    private void refreshHandleAfterRespawn() {
        try {
            Object liveHandle = bridge.handleOf(spawned.player());
            if (liveHandle != spawned.serverPlayer()) {
                if (DEBUG) {
                    logger.info("[debug " + name + "] handle swap detected");
                }
                Object listener = bridge.readConnectionField(liveHandle);
                spawned = new NmsBridge.SpawnedPlayer(liveHandle,
                        listener != null ? listener : spawned.gameListener(),
                        spawned.player(), spawned.player().getEntityId());
                // The respawn relocated us server-side; re-seat the emulator
                // (the teleport packet also arrives through the normal path).
                Location location = spawned.player().getLocation();
                physics.teleport(location.getX(), location.getY(), location.getZ());
                // Respawn re-armed the server's client-loaded gate; without
                // a fresh answer the next three seconds of sprint commands
                // and attacks would be silently dropped.
                declareClientLoaded();
                lastQueuedInput = null; // re-state the keyboard to the new listener
            }
        } catch (Throwable unresolved) {
            // Keep the old handle; the next tick retries.
        }
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
            if (velocity.entityId() == spawned.entityId() && !eventBasedKnockback) {
                // When EntityKnockbackEvent owns our knockback, ignore the
                // tracker's own SetEntityMotion echo for us — it carries the
                // once-decayed value that would override the full one.
                if (DEBUG) {
                    logger.info("[debug " + name + "] velocity applied (" + velocity.vx()
                            + "," + velocity.vy() + "," + velocity.vz() + ") emuZ=" + physics.z());
                }
                physics.applyVelocity(velocity.vx(), velocity.vy(), velocity.vz());
            }
            return;
        }
        if (inbound instanceof Inbound.Explosion explosion) {
            physics.addVelocity(explosion.kx(), explosion.ky(), explosion.kz());
            return;
        }
        if (inbound instanceof Inbound.KeepAlive keepAlive) {
            // Echo it back through the action line — a real client answers a
            // tick or so later (its own ping), and the server only needs the
            // reply before the next probe ~15 s out.
            actions.offer(new Action.KeepAlive(keepAlive.id()), now);
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
            if (DEBUG) {
                logger.info("[debug " + name + "] teleport sync id=" + sync.teleportId()
                        + " -> (" + x + "," + y + "," + z + ")");
            }
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
            climbTicks = 0;
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
        // Follow the target up terrain. When it has stood more than an auto-step
        // (STEP_HEIGHT) above us for long enough to be terrain rather than a
        // jump, keep advancing into the rise instead of holding range, and hop
        // (below) while still closing so running momentum carries us over the
        // lip. Without this the boxer halts at the foot of a block it could have
        // hopped; gating on a SUSTAINED rise keeps it from lurching every time
        // the target merely jumps in flat-ground combat.
        if (view.y() - physics.y() > ClientPhysics.STEP_HEIGHT) {
            climbTicks++;
        } else {
            climbTicks = 0;
        }
        boolean climbing = climbTicks >= CLIMB_SUSTAIN_TICKS;
        // The default ring is 0: the forward key NEVER releases — easing
        // off in the pocket drops sprint (vanilla needs impulse ≥ 0.8) and
        // the momentum that survives combos. Entity pushing resolves the
        // body contact, exactly as it does for a real W-holder. A raised
        // ring is deliberate range discipline: hold the pocket, back out
        // when overlapped.
        boolean inRange = distance <= movement.stopDistance();
        double forward;
        if (inRange && !climbing) {
            forward = distance < movement.stopDistance() - 0.8 ? -0.4 : 0.0;
        } else {
            forward = 1.0;
        }

        boolean sprinting = movement.sprint() && forward > 0.9;
        syncSprint(sprinting);
        // Jump to clear terrain. Hopping only once already blocked fails: the
        // collision has zeroed our horizontal velocity, so we rise straight up
        // against the step face and drop back down in place. Instead we hop
        // while still CLOSING on a target above us — leaving the ground with
        // running momentum intact is what arcs us forward over the block, the
        // way a player bunny-hops up stairs. The blocked case stays a fallback
        // for steps met head-on at our own level.
        boolean jump = physics.onGround() && (physics.horizontalCollision() || climbing);
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
                // Weave on approach and in the pocket alike. Keyboard
                // impulses are digital — A/D is ±1 or nothing; the weave
                // personality lives in the flip cadence, never in analog
                // softening no real client could send.
                if (--strafeFlipIn <= 0) {
                    strafeFlipIn = 8 + (int) (Math.abs(uuid.getMostSignificantBits() >> 8) % 7L);
                    strafeSign = -strafeSign;
                }
                return strafeSign;
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

    /**
     * The client-predicted half of pushEntities: the shove away from every
     * player whose box overlaps ours. Each party computes only its own half
     * (a real neighbour's client — or boxer brain — computes theirs), which
     * is why a W-holder bulldozes an AFK body instead of clipping through
     * it. Live server positions stand in for the interpolated remotes a
     * real client pushes against — entity interpolation is the one wire
     * detail the brain does not model.
     */
    private void pushAwayFromNeighbors() {
        Box self = physics.boundingBox();
        for (Player other : spawned.player().getWorld().getPlayers()) {
            if (other == spawned.player() || other.isDead()
                    || other.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location where = other.getLocation();
            if (!self.intersects(Box.player(where.getX(), where.getY(), where.getZ(),
                    ClientPhysics.PLAYER_WIDTH, ClientPhysics.PLAYER_HEIGHT))) {
                continue;
            }
            Vec3d shove = ClientPhysics.pushAway(physics.x(), physics.z(),
                    where.getX(), where.getZ());
            physics.addVelocity(shove.x(), 0.0, shove.z());
        }
    }

    /** A hit this boxer threw landed (server confirmation, owning thread). */
    void onHitLanded() {
        // Vanilla's sprint-attack proc, read exactly where attack() reads
        // it: the damage event fires INSIDE hurt(), so the sprint flag and
        // the attack meter still show their pre-clear values here. On a
        // proc the attacker's own motion multiplies ×0.6 horizontally and
        // the server clears its sprint flag (syncSprint's reconcile
        // re-arms it next tick, the toggle-sprint client rhythm). Under
        // OCM's restored 1.8 hit speed every spam click is full-meter, so
        // this fires per landed hit — exactly as it did for era players.
        // (The slow lands one brain tick after a zero-ping client would
        // apply it — the click decision predates this tick's travel.)
        if (serverSprinting && attackMeterFull()) {
            physics.multiplyHorizontalVelocity(0.6);
        }
        BoxerSettings.WTap wtap = settings.wtap();
        if (wtap.enabled() && wtapReleaseLeft == 0 && wtapCountdown < 0) {
            wtapCountdown = wtap.delayTicks();
        }
    }

    /** attack()'s meter gate: getAttackStrengthScale(0.5) > 0.9. */
    private boolean attackMeterFull() {
        try {
            return spawned.player().getAttackCooldown() > 0.9f;
        } catch (Throwable unsupported) {
            // No meter API → treat as the legacy always-full meter.
            return true;
        }
    }

    /**
     * Sprint state changes ship as the real PlayerCommand packets. The
     * cache reconciles against server truth first: vanilla clears the
     * ATTACKER's sprint flag on every full-meter sprint hit (and a respawn
     * resets it), and a real toggle-sprint client re-arms from its own
     * prediction with a fresh START_SPRINTING — a stale cache would leave
     * the boxer permanently unsprinting after its first punch on restored
     * 1.8 hit speed. The in-flight guard keeps a high-ping boxer from
     * re-sending while its previous command is still in transit.
     */
    private void syncSprint(boolean sprinting) {
        if (sprintActionsInFlight == 0 && serverSprinting
                && !spawned.player().isSprinting()) {
            serverSprinting = false;
        }
        if (serverSprinting == sprinting) {
            return;
        }
        serverSprinting = sprinting;
        sprintActionsInFlight++;
        actions.offer(new Action.Sprint(sprinting), System.nanoTime());
    }

    /**
     * The whole-keyboard state, queued on change — the exact rhythm a real
     * 1.21.2+ client ships its {@code player_input} packets with. The server
     * fires {@code PlayerInputEvent} from it and steers vehicles by it;
     * below 1.21.2 the factory resolves to nothing and no packet exists to
     * send. Strafe is vanilla's left-positive convention on both sides.
     */
    private void queueInput(@NotNull MoveInput input, long now) {
        Action.Input flags = new Action.Input(
                input.forward() > 0.0, input.forward() < 0.0,
                input.strafe() > 0.0, input.strafe() < 0.0,
                input.jump(), input.sneak(), input.sprint());
        if (flags.equals(lastQueuedInput)) {
            return;
        }
        lastQueuedInput = flags;
        actions.offer(flags, now);
    }

    /**
     * Answers the client-loaded handshake. 1.21.4+ servers arm a 60-tick
     * gate at listener construction and re-arm it on every respawn; until
     * the answer (or the timeout) arrives, sprint commands, interactions
     * and movement are silently dropped. A real client answers as soon as
     * its level renders — for a spawned brain that is immediately.
     */
    void declareClientLoaded() {
        try {
            Object packet = packetIO.playerLoaded();
            if (packet != null) {
                packetIO.dispatch(packet, spawned.gameListener());
            }
        } catch (Throwable failure) {
            logger.warning("[" + name + "] client-loaded handshake failed: " + failure);
        }
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
            if (DEBUG && moved) {
                logger.info("[debug " + name + "] move queued z=" + z
                        + " server=" + spawned.player().getLocation().getZ());
            }
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
                sprintActionsInFlight--;
                packetIO.dispatch(
                        packetIO.sprint(spawned.serverPlayer(), spawned.entityId(), sprint.start()),
                        spawned.gameListener());
                if (DEBUG) {
                    logger.info("[debug " + name + "] sprint cmd " + sprint.start()
                            + " -> live=" + spawned.player().isSprinting());
                }
            } else if (action instanceof Action.Input input) {
                Object packet = packetIO.playerInput(
                        input.forward(), input.backward(), input.left(), input.right(),
                        input.jump(), input.shift(), input.sprint());
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                }
            } else if (action instanceof Action.AcceptTeleport accept) {
                if (DEBUG) {
                    logger.info("[debug " + name + "] accept dispatched id=" + accept.id());
                }
                packetIO.dispatch(packetIO.acceptTeleport(accept.id()), spawned.gameListener());
            } else if (action instanceof Action.Attack attack) {
                if (attack.victim().isOnline()) {
                    packetIO.dispatch(packetIO.attack(attack.victim()), spawned.gameListener());
                }
            } else if (action instanceof Action.Swing) {
                packetIO.dispatch(packetIO.swing(), spawned.gameListener());
            } else if (action instanceof Action.KeepAlive keepAlive) {
                Object response = packetIO.keepAliveResponse(keepAlive.id());
                if (response != null) {
                    packetIO.dispatch(response, spawned.gameListener());
                }
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

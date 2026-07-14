package me.vexmc.simpleboxer.boxer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.Loadout;
import me.vexmc.simpleboxer.common.brain.Brain;
import me.vexmc.simpleboxer.common.brain.BrainOutput;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.knockback.KnockbackResolver;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One live boxer: the ServerPlayer handle, the client emulator, the utility-AI
 * brain, and the two latency lines between them. Everything here runs on the
 * boxer's owning thread except {@code outbound} (fed by the capture handler) and
 * the knockback listeners, which only enqueue.
 *
 * <p>The loop each tick: drain matured clientbound packets → resolve the winning
 * velocity into the client state → build the delayed {@link Perception} → run the
 * {@link Brain} for a {@link BrainOutput} (move input, aim, actions) → step the
 * physics → queue movement → dispatch matured actions → tick the ServerPlayer.
 * The brain lands behind exactly three seams (the move input, the aim angles, and
 * the action list) so every packet/physics/latency invariant is unchanged.</p>
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
    /** True where the server entity-ticks the boxer for us (Folia regions). */
    private final boolean serverTicksEntity;
    /**
     * True where knockback is captured from Paper's EntityKnockbackEvent (full,
     * at apply time) instead of polled from {@code hurtMarked} after the tick.
     * The poll races the region's own doTick on Folia and loses its horizontal
     * component to ground friction — the boxer "pops up" with no push.
     */
    private final boolean eventBasedKnockback;

    /** The whole decision brain (utility arbiter, aim spring, motor, clicks). */
    private final Brain brain;
    /** The single, ranked, deduplicated authority for received velocity. */
    private final KnockbackResolver knockback = new KnockbackResolver();
    private final KnockbackResolver.PhysicsSink physicsSink = new KnockbackResolver.PhysicsSink() {
        @Override
        public void applyVelocity(double x, double y, double z) {
            physics.applyVelocity(x, y, z);
        }

        @Override
        public void addVelocity(double x, double y, double z) {
            physics.addVelocity(x, y, z);
        }
    };

    /** Clientbound packets, fed cross-thread by the capture handler. */
    private final LatencyLine<CapturedPacket> outbound = new LatencyLine<>();
    /** Decided actions on their way to the server (the other half of RTT). */
    private final LatencyLine<Action> actions = new LatencyLine<>();
    /** World snapshots aging into the brain's delayed view of its target. */
    private final LatencyLine<TargetView> perception = new LatencyLine<>();
    /** Own attributes/effects aging in like UpdateAttributes packets would. */
    private final LatencyLine<PlayerTraits.Traits> traits = new LatencyLine<>();

    private volatile BoxerSettings settings;
    private volatile @Nullable Player target;
    private volatile boolean paused;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    /** ALIVE, or AWAITING_RESPAWN after a manual death until {@link #respawn()}. */
    private volatile State state = State.ALIVE;
    private volatile boolean respawnRequested;
    private volatile @Nullable Location deathSpot;

    /**
     * The boxer's worn kit. Published from any thread (API/GUI); applied to the
     * real inventory on the owning thread when {@code loadoutDirty} is set, so
     * equipment writes never race the brain or a Folia region tick.
     */
    private volatile Loadout loadout;
    private volatile boolean loadoutDirty;
    /** Set by {@link #retune}; the owning thread re-tunes the brain. */
    private volatile boolean settingsDirty;

    /** What the boxer believes about its target — one perception-delay old. */
    private record TargetView(double x, double y, double z, double eyeY,
            double vx, double vz, float yaw, boolean blocking, @NotNull Player entity) {}

    private @Nullable TargetView perceived;
    /** Previous matured target yaw, for the opponent-aim tracking-rate estimate. */
    private float prevTargetYaw;
    private boolean hasPrevTargetYaw;

    /** The crosshair the brain drove this tick — used for move packets + item use. */
    private float aimYaw;
    private float aimPitch;
    /** Whether an item is currently being used (block/eat/rod hold), for perception. */
    private boolean usingItem;
    /** Per-boxer block-change sequence for use-item packets (1.19+). */
    private int useSequence;
    /** A monotonic tick counter grouping knockback samples that share a server tick. */
    private long serverTick;

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

        record SelectSlot(int slot) implements Action {}

        record UseItem(boolean mainHand) implements Action {}

        record ReleaseUse() implements Action {}

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
            @NotNull Loadout loadout,
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
        this.loadout = loadout;
        // A non-empty starting kit is applied on the first brain tick (owning
        // thread), the same path runtime re-equips and respawn re-applies take.
        this.loadoutDirty = !loadout.isEmpty();
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
        // Seeded by identity: identical boxers decide identically — a
        // deterministic fixture is a debuggable fixture.
        this.brain = new Brain(settings, uuid.getLeastSignificantBits(),
                spawnLocation.getYaw(), spawnLocation.getPitch());
        this.aimYaw = spawnLocation.getYaw();
        this.aimPitch = spawnLocation.getPitch();
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
     * it. Offered to the resolver on the MELEE_KB channel.
     */
    void onKnockback(double vx, double vy, double vz) {
        knockback.offer(KnockbackResolver.Channel.MELEE_KB, vx, vy, vz, serverTick, System.nanoTime());
    }

    /**
     * Any server-side velocity a plugin (Mental's delivery, StarEnchants, vanilla)
     * applies through {@code PlayerVelocityEvent}: the FINAL absolute value Bukkit
     * will apply. This is the previously-lost signal for a viewerless boxer on
     * modern Paper. Offered on the highest-authority PLAYER_VELOCITY channel.
     */
    void onExternalVelocity(double vx, double vy, double vz) {
        knockback.offer(KnockbackResolver.Channel.PLAYER_VELOCITY, vx, vy, vz,
                serverTick, System.nanoTime());
    }

    /* ------------------------------------------------------------------ */
    /*  The brain tick (owning thread)                                     */
    /* ------------------------------------------------------------------ */

    void tick() {
        if (removed.get()) {
            return;
        }
        refreshHandleAfterRespawn();
        if (settingsDirty) {
            settingsDirty = false;
            brain.retune(settings);
        }
        if (loadoutDirty) {
            loadoutDirty = false;
            if (!syncKit()) {
                loadoutDirty = true;
            }
        }
        if (serverTicksEntity && hasAnchor) {
            reanchorServerPosition();
        }
        long now = System.nanoTime();
        long oneWay = TimeUnit.MILLISECONDS.toNanos(settings.pingMs()) / 2;
        serverTick++;

        // A dead boxer awaiting a manual respawn: only keep the connection alive.
        if (state == State.AWAITING_RESPAWN) {
            keepaliveOnly(now, oneWay);
            return;
        }

        // 1. Perceive: matured clientbound packets become client state, then the
        //    resolver applies the single winning velocity into the sim.
        for (CapturedPacket captured : outbound.drain(now, oneWay)) {
            for (Inbound inbound : packetIO.recognize(captured.packet())) {
                route(inbound, now);
            }
        }
        knockback.resolve(now, oneWay, physicsSink);
        snapshotTarget(now);
        TargetView matured = perception.drainLatest(now, oneWay);
        if (matured != null) {
            perceived = matured;
        }
        if (perceived != null && !perceived.entity().isOnline()) {
            perceived = null;
        }
        traits.offer(PlayerTraits.read(spawned.player()), now);
        PlayerTraits.Traits knownTraits = traits.drainLatest(now, oneWay);
        if (knownTraits != null) {
            physics.setWalkSpeed(knownTraits.walkSpeed());
            physics.setJumpBoostAmplifier(knownTraits.jumpBoostAmplifier());
        }

        // 2. Decide via the brain, unless paused (a paused client still receives
        //    packets — knockback flies you around while AFK too).
        MoveInput input;
        if (paused) {
            input = MoveInput.IDLE;
            syncSprint(false);
        } else {
            BrainOutput out = brain.tick(buildPerception(), collisionView, now / 1_000_000L);
            input = out.move();
            aimYaw = out.aimYaw();
            aimPitch = out.aimPitch();
            for (ActionIntent action : out.actions()) {
                lowerAction(action, now);
            }
            syncSprint(out.sprintDesire());
        }
        queueInput(input, now);
        physics.step(input, aimYaw, collisionView);
        pushAwayFromNeighbors();

        // 3. Report movement the way a real client does.
        queueMovement(now);

        // 4. Matured actions reach the server.
        for (Action action : actions.drain(now, oneWay)) {
            dispatch(action);
        }

        // 5. Ship the boxer's own pending knockback BEFORE the tick (classic
        //    servers): the poll fires PlayerVelocityEvent + broadcasts to viewers,
        //    and route() offers the resulting velocity to the resolver. Skipped
        //    where EntityKnockbackEvent owns the capture (it can't win this poll
        //    against the region's own tick).
        if (!eventBasedKnockback) {
            Object selfVelocity = bridge.drainHurtMarked(spawned.serverPlayer(), spawned.player());
            if (selfVelocity != null) {
                onOutboundPacket(new CapturedPacket(System.nanoTime(), selfVelocity));
            }
        }

        // 6. Tick the ServerPlayer where the server will not do it itself.
        if (serverTicksEntity) {
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
     * A dead-and-waiting boxer's minimal tick: answer keep-alives and teleport
     * acks so the connection survives, and perform the respawn when requested.
     */
    private void keepaliveOnly(long now, long oneWay) {
        for (CapturedPacket captured : outbound.drain(now, oneWay)) {
            for (Inbound inbound : packetIO.recognize(captured.packet())) {
                if (inbound instanceof Inbound.KeepAlive keepAlive) {
                    actions.offer(new Action.KeepAlive(keepAlive.id()), now);
                } else if (inbound instanceof Inbound.PositionSync sync) {
                    actions.offer(new Action.AcceptTeleport(sync.teleportId()), now);
                }
            }
        }
        for (Action action : actions.drain(now, oneWay)) {
            dispatch(action);
        }
        if (respawnRequested) {
            respawnRequested = false;
            manager.requestRespawn(this);
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
                brain.snapAim(location.getYaw(), location.getPitch());
                aimYaw = location.getYaw();
                aimPitch = location.getPitch();
                declareClientLoaded();
                lastQueuedInput = null; // re-state the keyboard to the new listener
                if (!loadout.isEmpty()) {
                    loadoutDirty = true;
                }
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
        org.bukkit.util.Vector velocity = currentTarget.getVelocity();
        perception.offer(new TargetView(location.getX(), location.getY(), location.getZ(),
                location.getY() + currentTarget.getEyeHeight(),
                velocity.getX(), velocity.getZ(), location.getYaw(),
                currentTarget.isBlocking(), currentTarget), now);
    }

    private void route(@NotNull Inbound inbound, long now) {
        if (inbound instanceof Inbound.Velocity velocity) {
            if (velocity.entityId() == spawned.entityId()) {
                // The tracker's own SetEntityMotion echo — a low-authority hint
                // the resolver ranks below the melee/velocity-event channels.
                knockback.offer(KnockbackResolver.Channel.MOTION_ECHO,
                        velocity.vx(), velocity.vy(), velocity.vz(), serverTick, now);
            }
            return;
        }
        if (inbound instanceof Inbound.Explosion explosion) {
            knockback.offerExplosion(explosion.kx(), explosion.ky(), explosion.kz(), serverTick, now);
            return;
        }
        if (inbound instanceof Inbound.KeepAlive keepAlive) {
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
                physics.applyVelocity(
                        sync.relativeX() ? velocity.x() : 0.0,
                        sync.relativeY() ? velocity.y() : 0.0,
                        sync.relativeZ() ? velocity.z() : 0.0);
            }
            float yaw = sync.relativeYaw() ? aimYaw + sync.yaw() : sync.yaw();
            float pitch = sync.relativePitch() ? aimPitch + sync.pitch() : sync.pitch();
            brain.snapAim(yaw, pitch);
            aimYaw = yaw;
            aimPitch = pitch;
            actions.offer(new Action.AcceptTeleport(sync.teleportId()), now);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Perception assembly + action lowering                              */
    /* ------------------------------------------------------------------ */

    /** Builds the immutable, delayed {@link Perception} the brain reasons over. */
    private @NotNull Perception buildPerception() {
        Perception.SelfState self = new Perception.SelfState(
                physics.x(), physics.y(), physics.z(), physics.velocity(),
                physics.onGround(), physics.horizontalCollision(),
                healthPct(), hungerPct(),
                usingItem ? Perception.UseItemState.USING : Perception.UseItemState.NONE,
                safeIsBlocking(spawned.player()));

        Perception.TargetState targetState = null;
        TargetView view = perceived;
        if (view != null) {
            double dx = view.x() - physics.x();
            double dz = view.z() - physics.z();
            double distance = Math.sqrt(dx * dx + dz * dz);
            double bearingToMe = Math.toDegrees(Math.atan2(-(physics.x() - view.x()),
                    (physics.z() - view.z())));
            double trackRate = 0.0;
            if (hasPrevTargetYaw) {
                trackRate = Math.abs(wrapDegrees(view.yaw() - prevTargetYaw));
            }
            prevTargetYaw = view.yaw();
            hasPrevTargetYaw = true;
            targetState = new Perception.TargetState(view.x(), view.y(), view.z(), view.eyeY(),
                    new Vec3d(view.vx(), 0.0, view.vz()), bearingToMe, trackRate, distance, view.blocking());
        } else {
            hasPrevTargetYaw = false;
        }

        return new Perception(self, targetState, Perception.TerrainView.OPEN,
                inventoryView(), new Perception.CombatState(attackMeter(), false, serverTick),
                settings.pingMs());
    }

    private @NotNull Perception.InventoryView inventoryView() {
        BoxerSettings.Items items = settings.items();
        PlayerInventory inventory = spawned.player().getInventory();
        boolean hasSword = ItemCategory.is(slot(inventory, items.weaponSlot()), ItemCategory.WEAPON);
        boolean hasRod = ItemCategory.is(slot(inventory, items.rodSlot()), ItemCategory.ROD);
        boolean hasPots = ItemCategory.is(slot(inventory, items.potSlot()), ItemCategory.POTION);
        boolean hasFood = ItemCategory.is(slot(inventory, items.foodSlot()), ItemCategory.FOOD);
        boolean hasShield = ItemCategory.is(inventory.getItemInOffHand(), ItemCategory.SHIELD)
                || ItemCategory.is(slot(inventory, items.blockSlot()), ItemCategory.SHIELD);
        return new Perception.InventoryView(hasSword, hasRod, hasPots, hasFood, hasShield,
                inventory.getHeldItemSlot());
    }

    private static @Nullable ItemStack slot(PlayerInventory inventory, int hotbarSlot) {
        return hotbarSlot >= 0 && hotbarSlot <= 8 ? inventory.getItem(hotbarSlot) : null;
    }

    /** Lowers one brain {@link ActionIntent} onto the action latency line. */
    private void lowerAction(@NotNull ActionIntent action, long now) {
        if (action instanceof ActionIntent.Attack) {
            TargetView view = perceived;
            if (view != null && view.entity().isOnline()) {
                actions.offer(new Action.Attack(view.entity()), now);
            }
        } else if (action instanceof ActionIntent.Swing) {
            actions.offer(new Action.Swing(), now);
        } else if (action instanceof ActionIntent.SelectSlot select) {
            actions.offer(new Action.SelectSlot(select.slot()), now);
        } else if (action instanceof ActionIntent.StartUse use) {
            actions.offer(new Action.UseItem(use.mainHand()), now);
        } else if (action instanceof ActionIntent.ReleaseUse) {
            actions.offer(new Action.ReleaseUse(), now);
        }
    }

    private double healthPct() {
        try {
            double max = maxHealth(spawned.player());
            return max <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, spawned.player().getHealth() / max));
        } catch (Throwable unsupported) {
            return 1.0;
        }
    }

    private double hungerPct() {
        try {
            return Math.max(0.0, Math.min(1.0, spawned.player().getFoodLevel() / 20.0));
        } catch (Throwable unsupported) {
            return 1.0;
        }
    }

    private double attackMeter() {
        try {
            return spawned.player().getAttackCooldown();
        } catch (Throwable unsupported) {
            return 1.0;
        }
    }

    private static boolean safeIsBlocking(Player player) {
        try {
            return player.isBlocking();
        } catch (Throwable unsupported) {
            return false;
        }
    }

    /**
     * The client-predicted half of pushEntities: the shove away from every player
     * whose box overlaps ours. Each party computes only its own half, which is why
     * a W-holder bulldozes an AFK body instead of clipping through it.
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
        // Vanilla's sprint-attack proc: a full-meter sprint hit multiplies the
        // attacker's own horizontal motion ×0.6 and clears its sprint flag
        // (syncSprint re-arms it). Read exactly where attack() reads it — the
        // damage event fires inside hurt(), so the flag/meter still read pre-clear.
        if (serverSprinting && attackMeterFull()) {
            physics.multiplyHorizontalVelocity(0.6);
        }
        brain.onHitLanded();
    }

    /** attack()'s meter gate: getAttackStrengthScale(0.5) > 0.9. */
    private boolean attackMeterFull() {
        try {
            return spawned.player().getAttackCooldown() > 0.9f;
        } catch (Throwable unsupported) {
            return true;
        }
    }

    /**
     * Sprint state changes ship as the real PlayerCommand packets. The cache
     * reconciles against server truth first (vanilla clears the sprint flag on a
     * full-meter sprint hit; a respawn resets it), then re-sends START_SPRINTING
     * exactly like a toggle-sprint client's auto re-arm. The in-flight guard keeps
     * a high-ping boxer from re-sending while its previous command is in transit.
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
     * 1.21.2+ client ships its {@code player_input} packets with.
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

    /** Answers the client-loaded handshake (1.21.4+); see the class javadoc. */
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
        float yaw = aimYaw;
        float pitch = aimPitch;
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
                sprintActionsInFlight--;
                packetIO.dispatch(
                        packetIO.sprint(spawned.serverPlayer(), spawned.entityId(), sprint.start()),
                        spawned.gameListener());
            } else if (action instanceof Action.Input input) {
                Object packet = packetIO.playerInput(
                        input.forward(), input.backward(), input.left(), input.right(),
                        input.jump(), input.shift(), input.sprint());
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                }
            } else if (action instanceof Action.AcceptTeleport accept) {
                packetIO.dispatch(packetIO.acceptTeleport(accept.id()), spawned.gameListener());
            } else if (action instanceof Action.Attack attack) {
                if (attack.victim().isOnline()) {
                    packetIO.dispatch(packetIO.attack(attack.victim()), spawned.gameListener());
                }
            } else if (action instanceof Action.Swing) {
                packetIO.dispatch(packetIO.swing(), spawned.gameListener());
            } else if (action instanceof Action.SelectSlot select) {
                Object packet = packetIO.setCarriedItem(select.slot());
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                }
            } else if (action instanceof Action.UseItem use) {
                Object packet = packetIO.useItem(use.mainHand(), ++useSequence, aimYaw, aimPitch);
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                    usingItem = true;
                }
            } else if (action instanceof Action.ReleaseUse) {
                Object packet = packetIO.releaseUseItem(++useSequence);
                if (packet != null) {
                    packetIO.dispatch(packet, spawned.gameListener());
                }
                usingItem = false;
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
    /*  Lifecycle (manual death / respawn)                                 */
    /* ------------------------------------------------------------------ */

    /** Manager/guard-internal: the boxer died under manual-death and is waiting. */
    void markAwaitingRespawn(@NotNull Location deathSpot) {
        this.deathSpot = deathSpot.clone();
        this.state = State.AWAITING_RESPAWN;
    }

    /** Manager-internal: the boxer has been respawned and is live again. */
    void markAlive() {
        this.state = State.ALIVE;
        this.respawnRequested = false;
        this.knockback.clear();
        if (!loadout.isEmpty()) {
            this.loadoutDirty = true;
        }
    }

    @Nullable Location deathSpot() {
        return deathSpot;
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
        this.settingsDirty = true;
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
    public @NotNull Loadout loadout() {
        return loadout;
    }

    @Override
    public void equip(@NotNull Loadout newLoadout) {
        this.loadout = newLoadout;
        this.loadoutDirty = true;
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
    public @NotNull State state() {
        return state;
    }

    @Override
    public void respawn() {
        if (state == State.AWAITING_RESPAWN) {
            this.respawnRequested = true;
        }
    }

    @Override
    public void remove() {
        manager.remove(this);
    }

    /**
     * Writes the operator kit onto the boxer's real inventory (owning thread). The
     * four armor slots and the offhand are stamped every dirty tick; the hotbar
     * weapon lands in the configured weapon slot. To avoid clobbering items the
     * boxer picked up, only the operator-declared slots are written — a picked-up
     * item in an untouched slot survives. In {@code lockLoadout} mode the classic
     * per-tick re-stamp is restored (a pure fixture whose gear never changes).
     *
     * @return true if the kit was written; false on a transient failure (retried).
     */
    private boolean syncKit() {
        Loadout kit = loadout;
        try {
            PlayerInventory inventory = spawned.player().getInventory();
            inventory.setHelmet(durable(kit.helmet()));
            inventory.setChestplate(durable(kit.chestplate()));
            inventory.setLeggings(durable(kit.leggings()));
            inventory.setBoots(durable(kit.boots()));
            inventory.setItemInOffHand(durable(kit.offHand()));
            // The main-hand kit item goes to the configured weapon slot; the boxer
            // selects hotbar slots itself via SetCarriedItem, so we don't force
            // the selected slot here (that would fight a mid-fight rod/pot swap).
            ItemStack main = durable(kit.mainHand());
            if (main != null || settings.items().lockLoadout()) {
                inventory.setItem(settings.items().weaponSlot(), main);
            }
            return true;
        } catch (Throwable failure) {
            logger.warning("[" + name + "] equipping the loadout failed: " + failure);
            return false;
        }
    }

    /** Stamp a kit piece Unbreakable so a tireless fixture's gear never wears out. */
    private static @Nullable ItemStack durable(@Nullable ItemStack item) {
        if (item == null) {
            return null;
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    @SuppressWarnings("deprecation") // GENERIC_MAX_HEALTH rename across the range
    private static double maxHealth(Player player) {
        for (Attribute attribute : Attribute.values()) {
            if (attribute.name().contains("MAX_HEALTH")) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    return instance.getValue();
                }
            }
        }
        return 20.0;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    /** Manager-internal: the actual despawn, on the owning thread. */
    void despawn(@NotNull String quitMessage) {
        if (removed.compareAndSet(false, true)) {
            bridge.remove(spawned, quitMessage);
            outbound.clear();
            actions.clear();
            knockback.clear();
        }
    }

    boolean isRemoved() {
        return removed.get();
    }

    @NotNull NmsBridge.SpawnedPlayer spawned() {
        return spawned;
    }
}

package me.vexmc.simpleboxer.common.physics;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The vanilla client's movement integrator for a player on land, distilled
 * from the Paper 1.21.11 Mojang-mapped decompile (every constant and the
 * integration order are pinned in docs/research/2026-06-06-client-motion-pins.md).
 * A boxer's trajectory comes from here exactly as a real player's comes from
 * their client: velocity packets replace motion, input accelerates it, the
 * world collides it, drags decay it — in that order, with the drag factor
 * chosen from the PRE-move ground state.
 *
 * <p>Deliberately unsupported (boxers spar on arena floors): fluids,
 * ladders, elytra, cobwebs, powder snow, block speed factors, vehicles.</p>
 */
public final class ClientPhysics {

    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_DRAG = 0.91;
    public static final double DEFAULT_WALK_SPEED = 0.1;
    public static final double DEFAULT_JUMP_STRENGTH = 0.42;
    public static final double SPRINT_SPEED_MULTIPLIER = 1.3;
    public static final double SNEAK_FACTOR = 0.3;
    /** {@code applyInput} scales held keys ×0.98 before travel sees them. */
    public static final double INPUT_SCALE = 0.98;
    /** 0.6³ — normalizes ground acceleration to the attribute on stone. */
    public static final double GROUND_ACCEL_MAGIC = 0.21600002;
    public static final double SPRINT_AIR_ACCEL = 0.025999999;
    public static final double WALK_AIR_ACCEL = 0.02;
    public static final double SPRINT_JUMP_PUSH = 0.2;
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double STEP_HEIGHT = 0.6;
    /** Vanilla collision epsilon ({@code CollisionUtil.COLLISION_EPSILON}). */
    static final double EPSILON = 1.0E-7;

    private double x;
    private double y;
    private double z;
    private double vx;
    private double vy;
    private double vz;
    private boolean onGround;
    private boolean horizontalCollision;
    private int noJumpDelay;

    private double walkSpeed = DEFAULT_WALK_SPEED;
    private double jumpStrength = DEFAULT_JUMP_STRENGTH;
    /** −1 = no effect; n ≥ 0 adds 0.1 × (n + 1) to the jump stamp. */
    private int jumpBoostAmplifier = -1;

    public ClientPhysics(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /* ------------------------------------------------------------------ */
    /*  Server-driven state changes (packets, after the perception delay)  */
    /* ------------------------------------------------------------------ */

    /** {@code ClientboundSetEntityMotionPacket}: REPLACES all three axes. */
    public void applyVelocity(double newVx, double newVy, double newVz) {
        this.vx = newVx;
        this.vy = newVy;
        this.vz = newVz;
    }

    /** Explosion knockback ADDS. */
    public void addVelocity(double dx, double dy, double dz) {
        this.vx += dx;
        this.vy += dy;
        this.vz += dz;
    }

    /**
     * Position packet: snap there. Velocity survives a vanilla teleport;
     * ground state re-derives from the next tick's collision.
     */
    public void teleport(double newX, double newY, double newZ) {
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.onGround = false;
    }

    public void setWalkSpeed(double walkSpeed) {
        this.walkSpeed = walkSpeed;
    }

    public void setJumpStrength(double jumpStrength) {
        this.jumpStrength = jumpStrength;
    }

    public void setJumpBoostAmplifier(int amplifier) {
        this.jumpBoostAmplifier = amplifier;
    }

    /* ------------------------------------------------------------------ */
    /*  The tick                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * One client tick: input → jump bookkeeping → acceleration → collide-move
     * → drags (pre-move friction). {@code yawDegrees} is the movement facing
     * (the brain owns rotation; physics only consumes it).
     */
    public void step(@NotNull MoveInput input, float yawDegrees, @NotNull CollisionView world) {
        // 1. Keyboard layer: sneak multiplier, then applyInput's ×0.98.
        double strafe = input.strafe();
        double forward = input.forward();
        if (input.sneak()) {
            strafe *= SNEAK_FACTOR;
            forward *= SNEAK_FACTOR;
        }
        strafe *= INPUT_SCALE;
        forward *= INPUT_SCALE;

        // 2. Jump bookkeeping (aiStep): max() preserves a stronger knock's
        //    vertical; the 10-tick delay only persists while the key is held.
        if (noJumpDelay > 0) {
            noJumpDelay--;
        }
        if (input.jump()) {
            if (onGround && noJumpDelay == 0) {
                jumpFromGround(input.sprint(), yawDegrees);
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }

        // 3. travelInAir: slipperiness from the block under the feet, read
        //    BEFORE the move — the launch tick of a knock decays at ground
        //    drag even though the move lifts the player.
        double slip = onGround
                ? world.slipperiness(floor(x), floor(y - 0.5000001), floor(z))
                : 1.0;
        double drag = slip * AIR_DRAG;

        double speed = onGround
                ? movementSpeed(input.sprint()) * (GROUND_ACCEL_MAGIC / (slip * slip * slip))
                : (input.sprint() ? SPRINT_AIR_ACCEL : WALK_AIR_ACCEL);
        accelerate(strafe, forward, speed, yawDegrees);

        move(world);

        this.vx *= drag;
        this.vz *= drag;
        this.vy = (vy - GRAVITY) * VERTICAL_DRAG;
    }

    private double movementSpeed(boolean sprinting) {
        return sprinting ? walkSpeed * SPRINT_SPEED_MULTIPLIER : walkSpeed;
    }

    private void jumpFromGround(boolean sprinting, float yawDegrees) {
        double boost = jumpBoostAmplifier >= 0 ? 0.1 * (jumpBoostAmplifier + 1) : 0.0;
        this.vy = Math.max(jumpStrength + boost, vy);
        if (sprinting) {
            double yaw = Math.toRadians(yawDegrees);
            this.vx += -Math.sin(yaw) * SPRINT_JUMP_PUSH;
            this.vz += Math.cos(yaw) * SPRINT_JUMP_PUSH;
        }
    }

    /** Entity.getInputVector: normalize only above unit length, scale, rotate. */
    private void accelerate(double strafe, double forward, double speed, float yawDegrees) {
        double lengthSqr = strafe * strafe + forward * forward;
        if (lengthSqr < 1.0E-7) {
            return;
        }
        if (lengthSqr > 1.0) {
            double length = Math.sqrt(lengthSqr);
            strafe /= length;
            forward /= length;
        }
        strafe *= speed;
        forward *= speed;
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        this.vx += strafe * cos - forward * sin;
        this.vz += forward * cos + strafe * sin;
    }

    /* ------------------------------------------------------------------ */
    /*  Collision (Entity.collide distilled)                               */
    /* ------------------------------------------------------------------ */

    private void move(@NotNull CollisionView world) {
        Box box = boundingBox();
        Vec3d moved = collide(box, vx, vy, vz, world);

        boolean collidedX = moved.x() != vx;
        boolean collidedY = moved.y() != vy;
        boolean collidedZ = moved.z() != vz;
        boolean landing = collidedY && vy < 0.0;

        // Step-up: grounded or landing, blocked horizontally — climb up to
        // STEP_HEIGHT, take the path with more horizontal progress (classic
        // up/across/down; outcome-identical to 1.20's candidate enumeration
        // on ordinary stairs and slabs).
        if ((onGround || landing) && (collidedX || collidedZ)) {
            Box base = landing ? box.offset(0.0, moved.y(), 0.0) : box;
            Vec3d up = collide(base, 0.0, STEP_HEIGHT, 0.0, world);
            Box upBox = base.offset(0.0, up.y(), 0.0);
            Vec3d across = collide(upBox, vx, 0.0, vz, world);
            if (across.horizontalDistanceSqr() > moved.horizontalDistanceSqr()) {
                Box acrossBox = upBox.offset(across.x(), 0.0, across.z());
                Vec3d down = collide(acrossBox, 0.0, -up.y(), 0.0, world);
                double yOffset = (landing ? moved.y() : 0.0) + up.y() + down.y();
                moved = new Vec3d(across.x(), yOffset, across.z());
                collidedX = moved.x() != vx;
                collidedZ = moved.z() != vz;
                collidedY = true;
            }
        }

        this.x += moved.x();
        this.y += moved.y();
        this.z += moved.z();
        this.horizontalCollision = collidedX || collidedZ;
        this.onGround = collidedY && vy < 0.0;
        if (collidedX) {
            this.vx = 0.0;
        }
        if (collidedY) {
            this.vy = 0.0;
        }
        if (collidedZ) {
            this.vz = 0.0;
        }
    }

    /** Axis-separated sweep, vanilla order: Y, then the larger horizontal. */
    private static Vec3d collide(Box box, double dx, double dy, double dz, CollisionView world) {
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return Vec3d.ZERO;
        }
        List<Box> shapes = world.collidingBoxes(box.expandTowards(dx, dy, dz));
        if (shapes.isEmpty()) {
            return new Vec3d(dx, dy, dz);
        }
        double ry = sweep(1, box, shapes, dy);
        Box moved = box.offset(0.0, ry, 0.0);
        double rx;
        double rz;
        if (Math.abs(dz) > Math.abs(dx)) {
            rz = sweep(2, moved, shapes, dz);
            moved = moved.offset(0.0, 0.0, rz);
            rx = sweep(0, moved, shapes, dx);
        } else {
            rx = sweep(0, moved, shapes, dx);
            moved = moved.offset(rx, 0.0, 0.0);
            rz = sweep(2, moved, shapes, dz);
        }
        return new Vec3d(rx, ry, rz);
    }

    /** Shapes.collide for one axis: clamp {@code d} against every shape. */
    private static double sweep(int axis, Box box, List<Box> shapes, double d) {
        if (d == 0.0) {
            return 0.0;
        }
        for (Box shape : shapes) {
            if (Math.abs(d) < EPSILON) {
                return 0.0;
            }
            if (!shape.overlapsPerpendicular(box, axis, EPSILON)) {
                continue;
            }
            if (d > 0.0) {
                double gap = shape.min(axis) - box.max(axis);
                if (gap >= -EPSILON && gap < d) {
                    d = Math.max(gap, 0.0);
                }
            } else {
                double gap = shape.max(axis) - box.min(axis);
                if (gap <= EPSILON && gap > d) {
                    d = Math.min(gap, 0.0);
                }
            }
        }
        return d;
    }

    /* ------------------------------------------------------------------ */
    /*  State                                                              */
    /* ------------------------------------------------------------------ */

    public @NotNull Box boundingBox() {
        return Box.player(x, y, z, PLAYER_WIDTH, PLAYER_HEIGHT);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public @NotNull Vec3d velocity() {
        return new Vec3d(vx, vy, vz);
    }

    public boolean onGround() {
        return onGround;
    }

    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}

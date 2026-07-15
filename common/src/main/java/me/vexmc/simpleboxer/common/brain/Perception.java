package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The immutable snapshot the whole brain reasons over for one tick — assembled
 * ONLY from matured (perception-delayed) data, so a laggy boxer decides on the
 * world it perceived {@code ping/2} ago. Pure: no Bukkit or NMS type crosses
 * this boundary, so every decision module is unit-testable against a crafted
 * {@code Perception}.
 */
public record Perception(
        @NotNull SelfState self,
        @Nullable TargetState target,
        @NotNull TerrainView terrain,
        @NotNull InventoryView inv,
        @NotNull CombatState combat,
        int pingMs) {

    /** Whether the boxer even has someone to fight this tick. */
    public boolean hasTarget() {
        return target != null;
    }

    /** The boxer's own kinematics and vitals. */
    public record SelfState(
            double x, double y, double z,
            @NotNull Vec3d velocity,
            boolean onGround,
            boolean horizontalCollision,
            double healthPct,
            double hungerPct,
            @NotNull UseItemState useItem,
            boolean blocking) {

        public @NotNull Vec3d position() {
            return new Vec3d(x, y, z);
        }
    }

    /**
     * The perceived (delayed) target. {@code bearingToMeYaw} is the yaw the
     * target would need to face the boxer; {@code oppTrackRateDegPerTick} is the
     * <em>unsigned</em> magnitude of how fast the target's actual aim is sweeping
     * toward/around the boxer, and {@code signedTrackRateDegPerTick} is the same
     * per-tick yaw delta <em>with its sign preserved</em> — positive when the
     * target's crosshair is sweeping one way, negative the other. Adaptive
     * strafing reads the signed rate to pick the side the opponent's aim is
     * <em>not</em> covering (juke opposite their sweep), where the unsigned
     * magnitude alone can only tell it that <em>some</em> track is happening.
     */
    public record TargetState(
            double x, double y, double z, double eyeY,
            @NotNull Vec3d velocity,
            double bearingToMeYaw,
            double oppTrackRateDegPerTick,
            double signedTrackRateDegPerTick,
            double distance,
            boolean blocking) {

        public @NotNull Vec3d position() {
            return new Vec3d(x, y, z);
        }
    }

    /**
     * Cheap terrain hints precomputed by the core adapter from the collision
     * view, so pure {@link Consideration}s can reason about geometry without a
     * live seam. Finer queries stay in the motor stage, which gets the
     * {@code CollisionView} directly.
     */
    public record TerrainView(
            boolean lineToTargetClear,
            double stepHeightAhead,
            boolean ledgeAhead,
            boolean stalled) {

        public static final TerrainView OPEN = new TerrainView(true, 0.0, false, false);
    }

    /** What the boxer is carrying, as availability flags + the live held slot. */
    public record InventoryView(
            boolean hasSword,
            boolean hasRod,
            boolean hasPots,
            boolean hasFood,
            boolean hasShield,
            int selectedSlot) {

        public static final InventoryView EMPTY =
                new InventoryView(false, false, false, false, false, 0);
    }

    /** Live combat timers/flags the brain needs for gating and routine triggers. */
    public record CombatState(
            double attackMeter,
            boolean mentalComboActive,
            long serverTick) {

        public static final CombatState IDLE = new CombatState(1.0, false, 0L);
    }

    /** Whether the boxer is mid-use of its held item (blocking, eating, casting). */
    public enum UseItemState {
        NONE,
        USING
    }
}

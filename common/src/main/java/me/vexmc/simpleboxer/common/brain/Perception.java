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

    /**
     * The boxer's own kinematics and vitals. {@code movementSpeed} is the aged
     * movement-speed attribute snapshot ({@code PlayerTraits}: Speed/Slowness,
     * armor and plugin modifiers — sprint modifier already stripped; vanilla
     * base 0.1) and {@code jumpBoostAmplifier} the aged Jump Boost level
     * ({@code -1} = none) — the same numbers the integrator runs on, so the
     * motor stack can predict takeoff kinematics instead of guessing from
     * velocity alone. A real client knows both via
     * ClientboundUpdateAttributes / UpdateMobEffect.
     *
     * <p>The fall block rides the same aged line: {@code maxHealth} is the
     * max-health attribute (modifiers included), {@code fallEpf} the
     * Protection/Feather-Falling EPF summed off the LIVE worn armor,
     * {@code safeFallDistance} the blocks a fall stays free (attribute from
     * 1.20.5, {@code 3 + jumpBoostLevel} before — identical observable),
     * {@code fallDamageMultiplier} the 1.20.5+ attribute (1.0 before), and
     * {@code slowFalling} whether the effect is live. Together they price a
     * deliberate ledge drop via {@link FallDamage} — knowledge a real client
     * has from its own equipment/attribute/effect packets.</p>
     */
    public record SelfState(
            double x, double y, double z,
            @NotNull Vec3d velocity,
            boolean onGround,
            boolean horizontalCollision,
            double healthPct,
            double hungerPct,
            @NotNull UseItemState useItem,
            boolean blocking,
            double movementSpeed,
            int jumpBoostAmplifier,
            double maxHealth,
            int fallEpf,
            double safeFallDistance,
            double fallDamageMultiplier,
            boolean slowFalling) {

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

    /**
     * Live combat timers/flags the brain needs for gating and routine triggers.
     * {@code potsLaunched} is the cumulative count of splash/lingering potions
     * the server has CONFIRMED launching for this boxer (a ThrownPotion spawn
     * with the boxer as shooter) — monotonic, never reset, so routines diff it
     * against a recorded baseline to tell a real throw from a use-item the
     * server silently swallowed.
     */
    public record CombatState(
            double attackMeter,
            boolean mentalComboActive,
            long serverTick,
            int potsLaunched) {

        public static final CombatState IDLE = new CombatState(1.0, false, 0L, 0);
    }

    /** Whether the boxer is mid-use of its held item (blocking, eating, casting). */
    public enum UseItemState {
        NONE,
        USING
    }
}

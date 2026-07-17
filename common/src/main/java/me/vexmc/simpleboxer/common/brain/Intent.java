package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * The abstract decision a winning {@link Goal} emits, decoupled from the
 * keyboard. A world-space desired move direction, where to look, an optional
 * item action, whether sprint is wanted, and a jump hint. The motor stage turns
 * this into a digital {@code MoveInput}; the action driver lowers the
 * {@link ActionIntent} onto the wire.
 */
public record Intent(
        @NotNull Vec3d moveDirWorld,
        @NotNull FacingIntent facing,
        @NotNull ActionIntent action,
        boolean wantSprint,
        @NotNull JumpHint jump) {

    public static final Intent IDLE = new Intent(
            Vec3d.ZERO, FacingIntent.faceMove(), ActionIntent.none(), false, JumpHint.NONE);

    /** Where the boxer wants to look this tick. */
    public sealed interface FacingIntent {

        /** Aim the crosshair at a world point (e.g. the target's chest). */
        record AimAt(double x, double y, double z) implements FacingIntent {}

        /** Look in the direction of travel (used while fleeing/repositioning). */
        record FaceMove() implements FacingIntent {}

        static @NotNull FacingIntent aimAt(double x, double y, double z) {
            return new AimAt(x, y, z);
        }

        static @NotNull FacingIntent faceMove() {
            return new FaceMove();
        }
    }

    /**
     * A single low-level action the core lowers onto the boxer's own game
     * listener. Higher-level behaviors (rod cast, pot throw, block, eat) are
     * sequenced by routines out of these primitives across ticks.
     */
    public sealed interface ActionIntent {

        record None() implements ActionIntent {}

        /** Attack the current target (the core attaches the live Player). */
        record Attack() implements ActionIntent {}

        /** Swing the arm (a whiffed/empty click). */
        record Swing() implements ActionIntent {}

        /** Change the selected hotbar slot (0-8). */
        record SelectSlot(int slot) implements ActionIntent {}

        /**
         * Begin using the held item. {@code kind} and {@code slot} are request
         * metadata consumed by {@link HandControl} — the kind decides the hold
         * lifecycle (BLOCK/EAT stays raised and must pair with a release;
         * THROW/CAST is momentary) and {@code slot} declares which hotbar slot
         * the use was written for, so the machine can refuse a use the hand is
         * not on ({@code -1} = no declaration). The core seam reads only
         * {@code mainHand}; the wire packet is unchanged.
         */
        record StartUse(boolean mainHand, @NotNull UseKind kind, int slot)
                implements ActionIntent {}

        /** Release the item currently in use (stop blocking/eating). */
        record ReleaseUse() implements ActionIntent {}

        /** How a use-item request behaves once started. */
        enum UseKind {
            /** A 1.8 sword block (the blockhit tap) — held; clicks stay free. */
            BLOCK(true),
            /** Eating/drinking — held for the consume; clicks are suppressed. */
            EAT(true),
            /** A splash-pot throw — momentary, nothing to release. */
            THROW(false),
            /** A rod cast — momentary, nothing to release. */
            CAST(false);

            private final boolean holds;

            UseKind(boolean holds) {
                this.holds = holds;
            }

            /** Whether this use stays raised until an explicit release. */
            public boolean holds() {
                return holds;
            }
        }

        None NONE = new None();

        static @NotNull ActionIntent none() {
            return NONE;
        }

        static @NotNull ActionIntent attack() {
            return new Attack();
        }

        static @NotNull ActionIntent swing() {
            return new Swing();
        }

        static @NotNull ActionIntent selectSlot(int slot) {
            return new SelectSlot(slot);
        }

        /** A held eat/drink of the item declared to be in {@code slot}. */
        static @NotNull ActionIntent eat(int slot) {
            return new StartUse(true, UseKind.EAT, slot);
        }

        /** A momentary splash-pot throw from {@code slot}. */
        static @NotNull ActionIntent throwUse(int slot) {
            return new StartUse(true, UseKind.THROW, slot);
        }

        /** A momentary rod cast from {@code slot}. */
        static @NotNull ActionIntent cast(int slot) {
            return new StartUse(true, UseKind.CAST, slot);
        }

        /** The blockhit tap's sword-block raise — emitted only by {@link HandControl}. */
        static @NotNull ActionIntent blockTap() {
            return new StartUse(true, UseKind.BLOCK, -1);
        }

        static @NotNull ActionIntent releaseUse() {
            return new ReleaseUse();
        }
    }

    /** Whether the motor should press jump this tick. */
    public enum JumpHint {
        NONE,
        JUMP
    }
}

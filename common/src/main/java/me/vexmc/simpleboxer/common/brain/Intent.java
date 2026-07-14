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

        /** Begin using the held item (rod cast, block raise, eat, pot throw). */
        record StartUse(boolean mainHand) implements ActionIntent {}

        /** Release the item currently in use (stop blocking/eating). */
        record ReleaseUse() implements ActionIntent {}

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

        static @NotNull ActionIntent startUse(boolean mainHand) {
            return new StartUse(mainHand);
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

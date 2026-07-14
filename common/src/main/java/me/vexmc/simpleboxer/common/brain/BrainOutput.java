package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import org.jetbrains.annotations.NotNull;

/**
 * Everything one brain tick produces, which the {@code BoxerImpl} adapter applies
 * at exactly three seams: the {@link MoveInput} it feeds {@code ClientPhysics}
 * (the digital keyboard), the aim angles it drives the crosshair to, and the
 * ordered list of {@link ActionIntent}s it lowers onto the action line
 * (attack/swing/slot-swap/use/release). {@code sprintDesire} reconciles through
 * the existing sprint-command path.
 */
public record BrainOutput(
        @NotNull MoveInput move,
        float aimYaw,
        float aimPitch,
        @NotNull List<ActionIntent> actions,
        boolean sprintDesire) {
}

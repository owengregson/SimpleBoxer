package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Feet planted: face the target and let the clicker do its work, but never move.
 * The {@code STAND} movement style (and the {@code dummy} preset) live here.
 */
public final class StandGoal implements Goal {

    private final Supplier<BoxerSettings> settings;

    public StandGoal(@NotNull Supplier<BoxerSettings> settings) {
        this.settings = settings;
    }

    @Override
    public @NotNull String id() {
        return "stand";
    }

    @Override
    public double utility(@NotNull Perception p) {
        return p.hasTarget()
                && settings.get().movement().style() == BoxerSettings.Movement.Style.STAND
                ? 0.55 : 0.0;
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        Perception.TargetState t = p.target();
        Intent.FacingIntent facing = t == null
                ? Intent.FacingIntent.faceMove()
                : Intent.FacingIntent.aimAt(t.x(), t.eyeY() - 0.4, t.z());
        return new Intent(Vec3d.ZERO, facing, Intent.ActionIntent.none(), false, Intent.JumpHint.NONE);
    }
}

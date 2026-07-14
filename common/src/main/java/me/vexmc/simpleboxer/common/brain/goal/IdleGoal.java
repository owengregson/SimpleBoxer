package me.vexmc.simpleboxer.common.brain.goal;

import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import org.jetbrains.annotations.NotNull;

/**
 * The always-available floor: when there is no target (or nothing else wants
 * anything), stand still and do nothing. A tiny positive utility keeps it from
 * being the arbiter's empty-field fallback only.
 */
public final class IdleGoal implements Goal {

    @Override
    public @NotNull String id() {
        return "idle";
    }

    @Override
    public double utility(@NotNull Perception p) {
        return p.hasTarget() ? 0.0 : 0.05;
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        return Intent.IDLE;
    }
}

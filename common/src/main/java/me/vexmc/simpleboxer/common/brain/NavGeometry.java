package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Pure geometry reasoning over the same {@link CollisionView} the integrator
 * collides against — the shared toolkit steering, the proactive jump, and the
 * local planner all build on. Every query is a "what would happen if the player
 * box were here" test against real block collision shapes (stairs/slabs/walls
 * included), so the boxer reasons about the exact geometry it will physically
 * collide with. Reuses {@link ClientPhysics#STEP_HEIGHT} so "auto-steppable vs
 * needs-a-jump" matches the integrator to the block.
 */
public final class NavGeometry {

    private NavGeometry() {}

    /** The tallest terrain rise a player can clear with a running jump (~1 block + lip). */
    public static final double MAX_JUMP_RISE = 1.25;
    /** How far ahead the motor probes for obstacles/steps, in blocks. */
    public static final double LOOK_AHEAD = 0.55;
    private static final double PROBE = 0.1;

    /** The standing player box for a foot position. */
    public static @NotNull Box playerBox(double x, double y, double z) {
        return Box.player(x, y, z, ClientPhysics.PLAYER_WIDTH, ClientPhysics.PLAYER_HEIGHT);
    }

    /** Whether any block collision box overlaps {@code box}. */
    public static boolean collides(@NotNull CollisionView world, @NotNull Box box) {
        List<Box> shapes = world.collidingBoxes(box);
        for (Box shape : shapes) {
            if (shape.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The upward shift the player box needs to move {@code ahead} blocks along
     * {@code dir} without colliding: 0 for flat ground, up to {@link #MAX_JUMP_RISE}
     * for a step/block to climb, and {@code > MAX_JUMP_RISE} (returned as
     * {@code MAX_JUMP_RISE + 1}) for an unclearable wall. {@code dir} is treated
     * as horizontal.
     */
    public static double riseAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double ahead) {
        Vec3d flat = dir.horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            return 0.0;
        }
        double dx = flat.x() * ahead;
        double dz = flat.z() * ahead;
        Box shifted = box.offset(dx, 0.0, dz);
        if (!collides(world, shifted)) {
            return 0.0;
        }
        for (double dy = PROBE; dy <= MAX_JUMP_RISE + 1.0E-6; dy += PROBE) {
            if (!collides(world, box.offset(dx, dy, dz))) {
                return dy;
            }
        }
        return MAX_JUMP_RISE + 1.0; // a wall taller than a jump clears
    }

    /**
     * Classifies the terrain {@code ahead} blocks along {@code dir}: STAND (flat),
     * STEP (auto-steppable ≤ {@link ClientPhysics#STEP_HEIGHT}), JUMP (a block to
     * hop with momentum), or BLOCKED (a wall). A drop-off reads as STAND here —
     * use {@link #ledgeAhead} to detect it.
     */
    public static @NotNull NodeKind classifyAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double ahead) {
        double rise = riseAhead(world, box, dir, ahead);
        if (rise <= ClientPhysics.STEP_HEIGHT + 1.0E-6) {
            return NodeKind.STAND;
        }
        if (rise <= MAX_JUMP_RISE) {
            return NodeKind.JUMP;
        }
        return NodeKind.BLOCKED;
    }

    /** True when a block to climb sits {@code ahead}: needs a momentum jump, not an auto-step. */
    public static boolean needsJumpAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double ahead) {
        return classifyAhead(world, box, dir, ahead) == NodeKind.JUMP;
    }

    /** True when a wall too tall to jump blocks travel {@code ahead}. */
    public static boolean wallAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double ahead) {
        return classifyAhead(world, box, dir, ahead) == NodeKind.BLOCKED;
    }

    /**
     * True when moving {@code ahead} along {@code dir} steps off an edge with no
     * ground within {@code maxDrop} below — the cue a fleeing boxer must not
     * sprint off a cliff.
     */
    public static boolean ledgeAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double ahead, double maxDrop) {
        Vec3d flat = dir.horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            return false;
        }
        double dx = flat.x() * ahead;
        double dz = flat.z() * ahead;
        Box shifted = box.offset(dx, 0.0, dz);
        if (collides(world, shifted)) {
            return false; // a wall/step, not a drop
        }
        for (double dy = PROBE; dy <= maxDrop + 1.0E-6; dy += PROBE) {
            if (collides(world, shifted.offset(0.0, -dy, 0.0))) {
                return false; // ground found within the drop budget
            }
        }
        return true;
    }

    /**
     * The support surface Y a player standing near {@code (x,z)} rests on, taken
     * from the tallest collision-box top at or just below {@code feetY}
     * (within {@code MAX_JUMP_RISE}); {@code Double.NaN} when there is no ground
     * (a void/ledge). Used by the planner to place cell floors.
     */
    public static double groundHeight(@NotNull CollisionView world, double x, double z,
            double feetY) {
        double half = ClientPhysics.PLAYER_WIDTH / 2.0;
        Box column = new Box(x - half, feetY - 4.0, z - half, x + half, feetY + MAX_JUMP_RISE, z + half);
        double best = Double.NaN;
        for (Box shape : world.collidingBoxes(column)) {
            if (shape.maxX() <= x - half || shape.minX() >= x + half
                    || shape.maxZ() <= z - half || shape.minZ() >= z + half) {
                continue; // no horizontal overlap with the standing footprint
            }
            double top = shape.maxY();
            if (top <= feetY + MAX_JUMP_RISE + 1.0E-6
                    && (Double.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }
}

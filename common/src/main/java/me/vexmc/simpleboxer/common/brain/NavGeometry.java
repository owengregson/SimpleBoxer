package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * ground within {@code maxDrop} below — the cue a boxer must not walk a drop
     * deeper than its fall budget. Resolved with ONE downward ground scan of the
     * shifted footprint ({@link #deepGroundHeight}), so the cost is independent
     * of the budget depth, and ground at exactly {@code maxDrop} below COUNTS as
     * ground (inclusive — a budget of N accepts an exactly-N drop, agreeing with
     * the planner's {@code drop > maxFall + EPS} refusal). An unreadable column
     * below reads as a ledge — the conservative Folia default.
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
        double cx = (shifted.minX() + shifted.maxX()) / 2.0;
        double cz = (shifted.minZ() + shifted.maxZ()) / 2.0;
        return Double.isNaN(deepGroundHeight(world, cx, cz, shifted.minY(), maxDrop));
    }

    /**
     * The support surface Y a player standing near {@code (x,z)} rests on, taken
     * from the tallest collision-box top at or just below {@code feetY}
     * (within {@code MAX_JUMP_RISE}); {@code Double.NaN} when there is no ground
     * (a void/ledge). Used by the planner to place cell floors.
     */
    public static double groundHeight(@NotNull CollisionView world, double x, double z,
            double feetY) {
        return groundHeight(world, x, z, feetY, ClientPhysics.PLAYER_WIDTH / 2.0);
    }

    /**
     * As {@link #groundHeight(CollisionView, double, double, double)} with an
     * explicit footprint half-width: the full player footprint takes the max
     * top the body would rest on, while a narrow probe (see
     * {@link #stairwiseWalkable}) samples sub-cell structure — the individual
     * lips of a stair block — that the footprint max hides.
     */
    public static double groundHeight(@NotNull CollisionView world, double x, double z,
            double feetY, double half) {
        Box column = new Box(x - half, feetY - 4.0, z - half, x + half, feetY + MAX_JUMP_RISE, z + half);
        double best = Double.NaN;
        for (Box shape : world.collidingBoxes(column)) {
            if (shape.maxX() <= x - half || shape.minX() >= x + half
                    || shape.maxZ() <= z - half || shape.minZ() >= z + half) {
                continue; // no horizontal overlap with the probe footprint
            }
            double top = shape.maxY();
            if (top <= feetY + MAX_JUMP_RISE + 1.0E-6
                    && (Double.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }

    /**
     * The first standable surface top at or below {@code feetY} within
     * {@code maxDrop} blocks under {@code (x, z)} — {@link #groundHeight}'s
     * deep-scan twin for deliberate descents, looking DOWN a drop instead of
     * around the feet. {@code Double.NaN} when nothing tops out inside the
     * window, or when any block of the CENTRE cell's column is unreadable (the
     * Folia contract: pricing a descent must never force a read across a region
     * boundary — an unreadable column is simply not a droppable edge; the
     * readability gate keys off the probe centre's cell). The
     * probe footprint is the player's, so a lip the body would catch on the
     * way down counts as the landing. The window is INCLUSIVE at
     * {@code feetY − maxDrop}: the column box extends one block further so a
     * surface exactly at the bound still strictly intersects.
     */
    public static double deepGroundHeight(@NotNull CollisionView world, double x, double z,
            double feetY, double maxDrop) {
        double half = ClientPhysics.PLAYER_WIDTH / 2.0;
        int cellX = (int) Math.floor(x);
        int cellZ = (int) Math.floor(z);
        int scanTop = (int) Math.floor(feetY);
        int scanBottom = (int) Math.floor(feetY - maxDrop) - 1;
        for (int y = scanTop; y >= scanBottom; y--) {
            if (!world.isReadable(cellX, y, cellZ)) {
                return Double.NaN;
            }
        }
        Box column = new Box(x - half, feetY - maxDrop - 1.0, z - half,
                x + half, feetY, z + half);
        double best = Double.NaN;
        for (Box shape : world.collidingBoxes(column)) {
            if (shape.maxX() <= x - half || shape.minX() >= x + half
                    || shape.maxZ() <= z - half || shape.minZ() >= z + half) {
                continue; // no horizontal overlap with the probe footprint
            }
            double top = shape.maxY();
            if (top <= feetY + 1.0E-6 && top >= feetY - maxDrop - 1.0E-6
                    && (Double.isNaN(best) || top > best)) {
                best = top;
            }
        }
        return best;
    }

    /** Probe half-width for sub-cell stair sampling — narrow enough to isolate one lip. */
    private static final double STAIR_PROBE_HALF = 0.05;
    /** Ground-sample stations across a cell along the travel direction (cell fractions). */
    private static final double[] STAIR_SAMPLES = {0.15, 0.35, 0.55, 0.75, 0.95};

    /**
     * True when cell {@code (cellX, cellZ)}, entered along the cardinal
     * {@code (dx, dz)} from a floor at {@code fromFloor}, is climbed by
     * successive vanilla auto-steps rather than a jump: sampling the ground at
     * five stations across the cell along the travel line, every station must
     * have ground and no station may RISE more than {@code STEP_HEIGHT} above
     * the previous one, ending within a step of {@code targetFloor} (the
     * cell's standing surface). A real stair block — a 0.5 bottom lip plus a
     * 1.0 back half — passes as two 0.5 sub-steps; a sheer 1.0 face fails on
     * its first station. Player-identity safe: this only refines the brain's
     * model of what the vanilla auto-step already climbs.
     */
    public static boolean stairwiseWalkable(@NotNull CollisionView world, int cellX, int cellZ,
            int dx, int dz, double fromFloor, double targetFloor) {
        double cx = cellX + 0.5;
        double cz = cellZ + 0.5;
        double prev = fromFloor;
        for (double t : STAIR_SAMPLES) {
            double top = groundHeight(world, cx + dx * (t - 0.5), cz + dz * (t - 0.5),
                    prev, STAIR_PROBE_HALF);
            if (Double.isNaN(top) || top - prev > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
                return false;
            }
            prev = top;
        }
        return Math.abs(targetFloor - prev) <= ClientPhysics.STEP_HEIGHT + 1.0E-6;
    }

    /** Coarse march spacing for {@link #stepFaceAhead} (catches ≥ 0.2-deep shapes). */
    private static final double FACE_MARCH = 0.2;
    /** Bisection refinements after the march: tolerance FACE_MARCH / 2⁸ ≈ 0.00078 blocks. */
    private static final int FACE_BISECT_STEPS = 8;

    /**
     * A face the moving box will contact within {@code maxAhead} blocks of
     * travel along {@code dir}: {@code distance} is the largest verified CLEAR
     * shift (an under-estimate of true contact by at most the bisection
     * tolerance) and {@code rise} the exact lift the tallest contacting shape
     * demands ({@code shape.maxY − feetY} — no probe-granularity rounding, so
     * a 1.0 step reads exactly 1.0).
     */
    public record StepFace(double distance, double rise) {}

    /**
     * March-then-bisect the box along {@code dir} for the first contact, or
     * {@code null} when the way is clear through {@code maxAhead}. Replaces
     * the fixed-distance point probe as ProactiveJump's face finder: the jump
     * needs the CONTACT DISTANCE (for the time-to-contact window), not just
     * "something within 0.75".
     */
    public static @Nullable StepFace stepFaceAhead(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d dir, double maxAhead) {
        Vec3d flat = dir.horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            return null;
        }
        double clear = 0.0;
        double contact = Double.NaN;
        for (double s = FACE_MARCH; s <= maxAhead + 1.0E-9; s += FACE_MARCH) {
            if (collides(world, box.offset(flat.x() * s, 0.0, flat.z() * s))) {
                contact = s;
                break;
            }
            clear = s;
        }
        if (Double.isNaN(contact)) {
            return null;
        }
        for (int i = 0; i < FACE_BISECT_STEPS; i++) {
            double mid = (clear + contact) / 2.0;
            if (collides(world, box.offset(flat.x() * mid, 0.0, flat.z() * mid))) {
                contact = mid;
            } else {
                clear = mid;
            }
        }
        Box contactBox = box.offset(flat.x() * contact, 0.0, flat.z() * contact);
        double rise = 0.0;
        for (Box shape : world.collidingBoxes(contactBox)) {
            if (shape.intersects(contactBox)) {
                rise = Math.max(rise, shape.maxY() - box.minY());
            }
        }
        return new StepFace(clear, rise);
    }

    /**
     * True when a mover at {@code refFloor} could brush PAST cell
     * {@code (cellX, cellZ)} at roughly the same level: readable, ground
     * within an auto-step of {@code refFloor}, and the standing box fits. The
     * clearance model counts everything else — walls, risers, drops, void,
     * missing headroom, unreadable cells — as an obstruction to keep a berth
     * from. Unreadable-first ordering is the Folia contract: no geometry read
     * ever happens on a cell {@code isReadable} refuses.
     */
    public static boolean standableLevel(@NotNull CollisionView world, int cellX, int cellZ,
            double refFloor) {
        int refY = (int) Math.floor(refFloor);
        for (int y = refY - 5; y <= refY + 2; y++) {
            if (!world.isReadable(cellX, y, cellZ)) {
                return false;
            }
        }
        double ground = groundHeight(world, cellX + 0.5, cellZ + 0.5, refFloor);
        if (Double.isNaN(ground) || Math.abs(ground - refFloor) > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
            return false;
        }
        return !collides(world, playerBox(cellX + 0.5, ground, cellZ + 0.5));
    }

    /**
     * The soft-clearance fraction for standing in cell {@code (cellX, cellZ)}:
     * 0.6 when an obstruction sits in the adjacent ring (Chebyshev 1), else
     * 0.0. Only the adjacent ring is probed — a second ring triples the probe
     * count (24 vs 8 ground probes per newly discovered cell) for the majority
     * of measured plan wall time, while the ring-1 term alone already prices
     * the hug lane out of open-field routes (see the planner's
     * {@code CLEARANCE_COST} arithmetic). Callers scale by their own cost
     * unit and MUST memoize per search.
     */
    public static double clearanceFraction(@NotNull CollisionView world, int cellX, int cellZ,
            double refFloor) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!standableLevel(world, cellX + dx, cellZ + dz, refFloor)) {
                    return 0.6;
                }
            }
        }
        return 0.0;
    }

    /**
     * Upper bound of the ceiling probe. The open-air jump apex is +1.2523 blocks
     * (0.42 impulse under 0.98 drag / 0.08 gravity — the motion pins), so a roof
     * at or beyond 1.3 of headroom can never clip the arc and reads as OPEN.
     */
    public static final double CEILING_PROBE_MAX = 1.3;

    /**
     * The headroom above the standing box: the largest probed upward shift (in
     * {@code PROBE} = 0.1 steps, up to {@link #CEILING_PROBE_MAX}) the box can
     * make without colliding — the column-above-the-head twin of {@link #riseAhead}'s
     * lifted probe. {@code 0.0} means a roof sits within the first probe step;
     * {@link Double#POSITIVE_INFINITY} means nothing overhead can clip a jump.
     * Quantized to the probe grid: callers gate on bands, not exact block math.
     */
    public static double ceilingGap(@NotNull CollisionView world, @NotNull Box box) {
        for (double dy = PROBE; dy <= CEILING_PROBE_MAX + 1.0E-6; dy += PROBE) {
            if (collides(world, box.offset(0.0, dy, 0.0))) {
                return dy - PROBE;
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}

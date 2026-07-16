package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * A Baritone-style 3D voxel A* — the pathfinder that routes a boxer around concave traps,
 * up off-line staircases, down drops, and across terrain it cannot see over. It is a
 * from-scratch reimplementation (Baritone is a client mod and cannot be bundled; its
 * {@code Movement}/{@code ActionCosts} model is used as an algorithm reference only). The
 * primary seam is {@link #plan(Vec3d, Vec3d, CollisionView, int, boolean, int)}, whose
 * {@link Route} says whether the goal cell was actually reached and at what floor level the
 * route ends — callers can tell a real route from a dead-end breadcrumb.
 * {@link #route(Vec3d, Vec3d, CollisionView, int, boolean)} keeps the legacy
 * {@link LocalPathPlanner}-shaped list seam over it (waypoint centres
 * {@code (cellX+0.5, floorY, cellZ+0.5)}).
 *
 * <p><b>Graph.</b> A node is a feet block {@code (x,y,z)} packed into a {@code long}
 * (26/12/26 bits). A cell's standable surface is snapped to real terrain via
 * {@link NavGeometry#groundHeight}/{@link NavGeometry#playerBox}/{@link NavGeometry#collides},
 * so nodes sit where a player would actually stand. Edges are one of
 * {@link MovementType}: {@code TRAVERSE}/{@code DIAGONAL} (level — including a stairwise
 * sub-step climb the vanilla auto-step walks, see {@link NavGeometry#stairwiseWalkable}),
 * {@code ASCEND} (+1 jump), {@code DESCEND} (−1 step), {@code FALL} (a dynamic-Y drop to the
 * first ground within a fall cap). Costs come from {@link MoveCosts} (sprint base plus
 * jump/fall penalties) plus a soft {@link #CLEARANCE_COST clearance surcharge}: entering a
 * cell whose nearest obstruction is at Chebyshev distance d costs an extra
 * {@code K·(2.5−d)/2.5} (d=1 → 0.6K, d=2 → 0.2K, else 0), so open-field routes bow a
 * two-cell berth around obstacles while narrow corridors stay routable (finite, additive to
 * {@code g}; the heuristic is untouched and stays an under-estimate).</p>
 *
 * <p><b>Heuristic (weighted, elevation-seeking).</b>
 * {@code h = octileHorizontal·SPRINT_ONE_BLOCK + Δy_up·JUMP_ONE_BLOCK}, with the vertical term
 * charged only when the goal is <em>above</em> the node (falling is cheap, so a downward goal
 * gets ~0 vertical term). For the current cardinal-only vertical moves the term is admissible;
 * it is deliberately structured as a mildly-inflated / weighted-A* term (bounded-suboptimal,
 * favouring elevation-seeking and search speed) — do not rely on strict optimality of
 * climbing routes.</p>
 *
 * <p><b>Anytime partial.</b> On budget or bound exhaustion the route is reconstructed from
 * the min-{@code h} node still on the OPEN frontier — where the search could have kept going
 * (e.g. toward stairs) — rather than from any closed dead end: the heuristic's cheap vertical
 * term otherwise parks every failed partial on the cell directly under an elevated goal. When
 * the frontier is empty (space exhausted) the globally best node is used, and either way the
 * partial must have advanced ≥ 2 cells or {@link Optional#empty()} is returned.</p>
 *
 * <p><b>Folia safety.</b> The search is clamped to a caller-chosen horizontal box (capped at
 * {@link #MAX_EXTENT_CAP}) and a {@link #Y_BAND} vertical band, and every neighbour cell's
 * column (footprint + head + fall column) is gated through {@link CollisionView#isReadable} —
 * an unreadable cell yields no edge, so the search halts at the loaded/region frontier and
 * returns a partial that lives entirely in readable space (the clearance probes are gated the
 * same way inside {@link NavGeometry#standableLevel}). Owning-thread; deterministic (fixed
 * neighbour order + insertion-order tie-breaks; the frontier scan takes a strict minimum with
 * a total key tie-break, so it is iteration-order independent).</p>
 */
public final class BaritoneStylePlanner {

    /** Default half-width of the search box, in block cells, measured from the start cell. */
    public static final int MAX_EXTENT = 10;
    /** Hard cap on a caller-supplied search-box half-width (region-safety bound; the
     *  {@code isReadable} column gate remains the true Folia contract either way). */
    public static final int MAX_EXTENT_CAP = 32;
    /** Half-height of the search band, in blocks, measured from the start feet level. */
    public static final int Y_BAND = 8;
    /** Deepest drop a single FALL/DESCEND edge may cover (fall-damage-ish cap). */
    public static final int MAX_FALL = 3;

    /**
     * Clearance surcharge scale, in tick units: half a sprinted block. With the
     * {@code (2.5−d)/2.5} ring fractions this prices a wall-adjacent cell at +1.0691
     * ticks and a one-off cell at +0.3564 — enough that beside a 7-cell wall the
     * one-lane-out route (two extra diagonals, +2.9524 ticks) saves more in
     * surcharges (9×1.0691 − 10×0.3564 = 6.0584) than it spends, while a 1-wide
     * corridor (every cell +1.0691, no alternative) stays routable.
     */
    static final double CLEARANCE_COST = 0.5 * MoveCosts.SPRINT_ONE_BLOCK;

    private static final double STEP_HEIGHT = ClientPhysics.STEP_HEIGHT; // 0.6
    private static final double MAX_JUMP_RISE = NavGeometry.MAX_JUMP_RISE; // 1.25
    private static final double EPS = 1.0E-6;

    /** The eight horizontal neighbour offsets, iterated in a fixed order for determinism. */
    private static final int[][] NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** Kind of a resolved edge; mirrors Baritone's {@code Moves} minus mine/place/parkour. */
    public enum MovementType {
        TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL
    }

    /**
     * A self-describing planning result: the waypoint breadcrumb, whether the search
     * actually REACHED the (clamped) goal cell ({@code complete}), and the floor Y the
     * route's final cell stands on ({@code endFloorY}) — enough for a caller to refuse
     * a dead-end partial that gains no elevation toward a raised target.
     */
    public record Route(@NotNull List<Vec3d> waypoints, boolean complete, double endFloorY) {}

    /**
     * The legacy list seam ({@link LocalPathPlanner}-shaped): the waypoints of
     * {@link #plan} at the default {@link #MAX_EXTENT}, complete or partial alike.
     */
    public @NotNull Optional<List<Vec3d>> route(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget, boolean allowJump) {
        return plan(start, goal, world, budget, allowJump, MAX_EXTENT).map(Route::waypoints);
    }

    /**
     * Plans a route from {@code start} toward {@code goal} over 3D terrain, clamped to an
     * {@code extent}-cell box (capped at {@link #MAX_EXTENT_CAP}) + {@link #Y_BAND} band and
     * capped at {@code budget} node expansions. When {@code allowJump} is false the search
     * refuses {@code ASCEND} edges (routes around a step-up instead of over it) — stairwise
     * sub-step climbs are TRAVERSE and stay legal. Returns the collapsed waypoint breadcrumb
     * from just-after-start to the (possibly clamped) goal, an anytime partial toward it, or
     * {@link Optional#empty()} when nothing better than the start cell is reachable.
     */
    public @NotNull Optional<Route> plan(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget, boolean allowJump, int extent) {
        int maxExtent = clamp(extent, 1, MAX_EXTENT_CAP);
        int sx = floor(start.x());
        int sz = floor(start.z());

        double startGround = NavGeometry.groundHeight(world, sx + 0.5, sz + 0.5, start.y());
        if (Double.isNaN(startGround)) {
            startGround = start.y(); // no ground read (void/edge) — anchor on the raw feet
        }
        int sy = floor(startGround);

        // Clamp the goal into the box + band so a far/raised target still yields a breadcrumb.
        int gx = clamp(floor(goal.x()), sx - maxExtent, sx + maxExtent);
        int gz = clamp(floor(goal.z()), sz - maxExtent, sz + maxExtent);
        double goalGround = NavGeometry.groundHeight(world, gx + 0.5, gz + 0.5, goal.y());
        int gy = clamp(Double.isNaN(goalGround) ? floor(goal.y()) : floor(goalGround),
                sy - Y_BAND, sy + Y_BAND);

        long startKey = key(sx, sy, sz);
        long goalKey = key(gx, gy, gz);

        if (startKey == goalKey) {
            return Optional.of(new Route(
                    List.of(new Vec3d(gx + 0.5, startGround, gz + 0.5)), true, startGround));
        }

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Node> nodes = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> clearance = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Frontier> open = new PriorityQueue<>((a, b) -> {
            int byF = Double.compare(a.f, b.f);
            return byF != 0 ? byF : Long.compare(a.seq, b.seq); // deterministic FIFO tie-break
        });

        Node startNode = new Node(sx, sy, sz, startGround);
        nodes.put(startKey, startNode);
        gScore.put(startKey, 0.0);

        long seq = 0;
        long bestKey = startKey;
        double bestH = heuristic(sx, sy, sz, gx, gy, gz);
        open.add(new Frontier(startKey, bestH, seq++));

        int expansions = 0;
        while (!open.isEmpty()) {
            Frontier current = open.poll();
            long curKey = current.key;
            if (!closed.add(curKey)) {
                continue; // a stale duplicate left over from a decrease-key
            }
            if (curKey == goalKey) {
                return Optional.of(new Route(reconstruct(nodes, cameFrom, curKey), true,
                        nodes.get(curKey).floor));
            }
            if (++expansions > budget) {
                break; // spent the tick's budget — fall through to the bestSoFar partial
            }

            Node cur = nodes.get(curKey);
            double curG = gScore.get(curKey);

            for (int[] step : NEIGHBOURS) {
                int dx = step[0];
                int dz = step[1];
                int nx = cur.x + dx;
                int nz = cur.z + dz;
                if (Math.abs(nx - sx) > maxExtent || Math.abs(nz - sz) > maxExtent) {
                    continue; // outside the bounded (region-safe) search box
                }
                // Folia gate: the neighbour column (fall column + head clearance) must be readable
                // before we probe its geometry — an unreadable cell is a wall (no edge).
                if (!columnReadable(world, nx, nz, cur.y - MAX_FALL - 1, cur.y + 2)) {
                    continue;
                }

                double nGround = NavGeometry.groundHeight(world, nx + 0.5, nz + 0.5, cur.floor);
                if (Double.isNaN(nGround)) {
                    continue; // void / nothing standable within the reachable window
                }
                int ny = floor(nGround);
                if (Math.abs(ny - sy) > Y_BAND) {
                    continue; // outside the vertical band
                }
                if (NavGeometry.collides(world, NavGeometry.playerBox(nx + 0.5, nGround, nz + 0.5))) {
                    continue; // a block occupies the body here (wall / missing headroom)
                }

                boolean diagonal = dx != 0 && dz != 0;
                double dy = nGround - cur.floor;
                MovementType type;
                double edge;
                if (dy > STEP_HEIGHT + EPS) {
                    if (!diagonal && dy <= MAX_JUMP_RISE + EPS
                            && NavGeometry.stairwiseWalkable(world, nx, nz, dx, dz,
                                    cur.floor, nGround)) {
                        // A real staircase block: the vanilla auto-step climbs its 0.5
                        // lips without a jump — a flat traverse even for the walk-only
                        // pass (footprint-max ground used to read it as a +1.0 ASCEND).
                        type = MovementType.TRAVERSE;
                        edge = MoveCosts.SPRINT_ONE_BLOCK;
                    } else if (!allowJump || diagonal || dy > MAX_JUMP_RISE + EPS) {
                        continue;
                    } else {
                        // A climb: needs a jump, cardinal only, within a single-block rise.
                        type = MovementType.ASCEND;
                        edge = MoveCosts.SPRINT_ONE_BLOCK + MoveCosts.JUMP_ONE_BLOCK;
                    }
                } else if (dy < -STEP_HEIGHT - EPS) {
                    // A drop: cardinal only, within the fall cap.
                    double drop = -dy;
                    if (diagonal || drop > MAX_FALL + EPS) {
                        continue;
                    }
                    if (drop <= 1.0 + EPS) {
                        type = MovementType.DESCEND;
                        edge = MoveCosts.SPRINT_ONE_BLOCK + MoveCosts.CENTER_AFTER_FALL;
                    } else {
                        type = MovementType.FALL;
                        edge = MoveCosts.WALK_OFF_BLOCK + MoveCosts.fallTicks(drop)
                                + MoveCosts.CENTER_AFTER_FALL;
                    }
                } else {
                    // Level (or an auto-steppable lip): a flat traverse.
                    if (diagonal) {
                        if (!cornerOpen(world, cur.x, cur.z, dx, dz, cur.floor)) {
                            continue; // would squeeze diagonally between two solid corners
                        }
                        type = MovementType.DIAGONAL;
                        edge = MoveCosts.SPRINT_ONE_BLOCK_DIAGONAL;
                    } else {
                        type = MovementType.TRAVERSE;
                        edge = MoveCosts.SPRINT_ONE_BLOCK;
                    }
                }
                assert type != null;

                long nKey = key(nx, ny, nz);
                if (closed.contains(nKey)) {
                    continue;
                }
                // Soft clearance surcharge (memoized): a finite, ≥0 addition to g only,
                // so h stays an under-estimate and corridors never close.
                Double berth = clearance.get(nKey);
                if (berth == null) {
                    berth = CLEARANCE_COST * NavGeometry.clearanceFraction(world, nx, nz, nGround);
                    clearance.put(nKey, berth);
                }
                double tentative = curG + edge + berth;
                Double known = gScore.get(nKey);
                if (known == null || tentative < known - EPS) {
                    gScore.put(nKey, tentative);
                    nodes.put(nKey, new Node(nx, ny, nz, nGround));
                    cameFrom.put(nKey, curKey);
                    double h = heuristic(nx, ny, nz, gx, gy, gz);
                    open.add(new Frontier(nKey, tentative + h, seq++));
                    if (h < bestH - EPS) {
                        bestH = h;
                        bestKey = nKey;
                    }
                }
            }
        }

        // Anytime fallback: prefer the min-h node still on the OPEN frontier — where the
        // search could have kept going (e.g. toward the stairs) — over a closed dead end;
        // the heuristic's cheap vertical term otherwise parks every failed partial on the
        // cell directly under an elevated goal. Strict minimum with a total key tie-break
        // keeps this deterministic regardless of heap iteration order. Fall back to the
        // globally best node when the frontier is empty, and keep the ≥2-cell anti-jitter bar.
        long partialKey = startKey;
        double partialH = Double.MAX_VALUE;
        for (Frontier f : open) {
            if (closed.contains(f.key)) {
                continue; // a stale duplicate of an already-expanded node
            }
            Node n = nodes.get(f.key);
            double h = heuristic(n.x, n.y, n.z, gx, gy, gz);
            if (h < partialH - EPS || (h < partialH + EPS && f.key < partialKey)) {
                partialH = h;
                partialKey = f.key;
            }
        }
        if (partialKey == startKey) {
            partialKey = bestKey;
        }
        if (partialKey != startKey) {
            Node best = nodes.get(partialKey);
            int advanced = Math.max(Math.abs(best.x - sx), Math.abs(best.z - sz));
            if (advanced >= 2) {
                return Optional.of(new Route(reconstruct(nodes, cameFrom, partialKey), false,
                        best.floor));
            }
        }
        return Optional.empty();
    }

    /** {@code h = octileHorizontal·SPRINT + Δy_up·JUMP} — see the class javadoc on the vertical term. */
    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int dmin = Math.min(dx, dz);
        int dmax = Math.max(dx, dz);
        double horizontal = ((dmax - dmin) + Math.sqrt(2.0) * dmin) * MoveCosts.SPRINT_ONE_BLOCK;
        int up = gy - y;
        return up > 0 ? horizontal + up * MoveCosts.JUMP_ONE_BLOCK : horizontal;
    }

    /**
     * True when a flat diagonal from {@code (cx,cz)} by {@code (dx,dz)} clips no solid corner: BOTH
     * orthogonal cells the 0.6-wide body brushes past must be standable at (roughly) the same level.
     */
    private static boolean cornerOpen(@NotNull CollisionView world, int cx, int cz,
            int dx, int dz, double fromFloor) {
        return levelFlank(world, cx + dx, cz, fromFloor) && levelFlank(world, cx, cz + dz, fromFloor);
    }

    private static boolean levelFlank(@NotNull CollisionView world, int fx, int fz, double fromFloor) {
        double ground = NavGeometry.groundHeight(world, fx + 0.5, fz + 0.5, fromFloor);
        if (Double.isNaN(ground)) {
            return false;
        }
        if (NavGeometry.collides(world, NavGeometry.playerBox(fx + 0.5, ground, fz + 0.5))) {
            return false;
        }
        return Math.abs(ground - fromFloor) <= STEP_HEIGHT + EPS; // level enough to brush past
    }

    private static boolean columnReadable(@NotNull CollisionView world, int cx, int cz,
            int loY, int hiY) {
        for (int y = loY; y <= hiY; y++) {
            if (!world.isReadable(cx, y, cz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks {@code cameFrom} back to the start, then emits the waypoint centres from just-after-start
     * to {@code endKey}, collapsing straight collinear runs so only turn points and elevation changes
     * survive.
     */
    private static @NotNull List<Vec3d> reconstruct(@NotNull Map<Long, Node> nodes,
            @NotNull Map<Long, Long> cameFrom, long endKey) {
        List<Long> keys = new ArrayList<>();
        long cell = endKey;
        keys.add(cell);
        while (cameFrom.containsKey(cell)) {
            cell = cameFrom.get(cell);
            keys.add(cell);
        }
        java.util.Collections.reverse(keys); // start -> end

        List<Vec3d> out = new ArrayList<>();
        int n = keys.size();
        for (int i = 1; i < n; i++) { // skip index 0 (the start cell)
            boolean keep;
            if (i == n - 1) {
                keep = true; // always keep the destination
            } else {
                Node prev = nodes.get(keys.get(i - 1));
                Node curr = nodes.get(keys.get(i));
                Node next = nodes.get(keys.get(i + 1));
                int pdx = Integer.signum(curr.x - prev.x);
                int pdz = Integer.signum(curr.z - prev.z);
                int ndx = Integer.signum(next.x - curr.x);
                int ndz = Integer.signum(next.z - curr.z);
                boolean turn = pdx != ndx || pdz != ndz;
                // Keep every elevation change too (like LocalPathPlanner's "stepped"): a constant-slope
                // staircase has an unchanging direction, so without this each step-up/step-down would be
                // collapsed away and the motor/pre-jump would lose the cue to climb or drop.
                boolean stepped = curr.y != prev.y;
                keep = turn || stepped;
            }
            if (keep) {
                Node c = nodes.get(keys.get(i));
                out.add(new Vec3d(c.x + 0.5, c.floor, c.z + 0.5));
            }
        }
        return out;
    }

    // --- 3D cell packing (26/12/26 bits into a long) ---------------------------

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (long) (z & 0x3FFFFFF);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** A resolved standable cell: feet block {@code (x,y,z)} and the exact surface Y under the feet. */
    private record Node(int x, int y, int z, double floor) {}

    /** A queued cell keyed by A* priority {@code f = g + h}; {@code seq} makes ties deterministic. */
    private record Frontier(long key, double f, long seq) {}
}

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
 * {@code Movement}/{@code ActionCosts} model is used as an algorithm reference only).
 *
 * <p><b>Time-sliced.</b> The primary seam is {@link #begin} + {@link #step}: the whole
 * search — frontier, scores, memoized clearance, best-so-far — lives in a
 * {@link SearchState} the caller parks in its own memory, and {@link #step} advances it a
 * bounded number of node expansions at a time. A search that cannot finish inside one
 * decision tick simply resumes next tick; the caller keeps steering meanwhile. Slicing is
 * exactly equivalent to a one-shot run: the state carries everything, so N slices of M
 * expansions reconstruct the same route (and cost) as one slice of N·M.
 * {@link #plan(Vec3d, Vec3d, CollisionView, int, boolean, int)} keeps the legacy synchronous
 * seam over it, and {@link #route(Vec3d, Vec3d, CollisionView, int, boolean)} the
 * {@link LocalPathPlanner}-shaped list seam (waypoint centres
 * {@code (cellX+0.5, floorY, cellZ+0.5)}).</p>
 *
 * <p><b>Graph.</b> A node is a feet block {@code (x,y,z)} packed into a {@code long}
 * (26/12/26 bits). A cell's standable surface is snapped to real terrain via
 * {@link NavGeometry#groundHeight}/{@link NavGeometry#playerBox}/{@link NavGeometry#collides},
 * so nodes sit where a player would actually stand. Edges are one of
 * {@link MovementType}: {@code TRAVERSE}/{@code DIAGONAL} (level — including a stairwise
 * sub-step climb the vanilla auto-step walks, see {@link NavGeometry#stairwiseWalkable}),
 * {@code ASCEND} (+1 jump), {@code DESCEND} (−1 step), {@code FALL} (a dynamic-Y drop to the
 * first ground within the plan's {@link FallBudget}). A drop deeper than the shallow ground
 * window is resolved by {@link NavGeometry#deepGroundHeight} — per-block readability-gated —
 * so a survivable ledge hop is a first-class edge, priced at its travel ticks PLUS
 * {@link #DAMAGE_PENALTY_TICKS} per predicted damage point: a walking descent wins whenever
 * it is comparably close, and a gratuitous detour loses to a cheap hop. Costs come from
 * {@link MoveCosts} plus a soft {@link #CLEARANCE_COST clearance surcharge} on ring-1
 * obstructions (finite, additive to {@code g}; the heuristic stays an under-estimate).</p>
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
 * {@link #MAX_EXTENT_CAP}) and a vertical band ({@link #Y_BAND} up; down extended to cover
 * the fall budget, see {@link #begin}), and every neighbour cell's column is gated through
 * {@link CollisionView#isReadable} — an unreadable cell yields no edge, so the search halts
 * at the loaded/region frontier and returns a partial that lives entirely in readable space
 * (deep-drop scans re-gate their own column per block inside
 * {@link NavGeometry#deepGroundHeight}). Owning-thread; deterministic (fixed neighbour order
 * + insertion-order tie-breaks; the frontier scan takes a strict minimum with a total key
 * tie-break, so it is iteration-order independent).</p>
 */
public final class BaritoneStylePlanner {

    /** Default half-width of the search box, in block cells, measured from the start cell. */
    public static final int MAX_EXTENT = 10;
    /** Hard cap on a caller-supplied search-box half-width (region-safety bound; the
     *  {@code isReadable} column gate remains the true Folia contract either way). */
    public static final int MAX_EXTENT_CAP = 32;
    /** Half-height of the search band, in blocks, measured from the start feet level.
     *  Upward this is absolute; downward it stretches to the fall budget (+2 blocks of
     *  post-landing walking room) so a within-budget drop is always representable. */
    public static final int Y_BAND = 8;
    /** Deepest drop the DEFAULT budget allows a single FALL/DESCEND edge to cover — the
     *  classic zero-damage cap (safe fall is 3). Plans made with a wider
     *  {@link FallBudget} extend past this, never the shallow ground window itself. */
    public static final int MAX_FALL = 3;

    /**
     * Ticks charged per predicted point of fall damage on a FALL edge — the
     * exchange rate between health and travel time. Calibration (the planner
     * test battery pins the exact totals): on the 5-block-platform arena the
     * direct hop totals ≈ 56.77 ticks (2 points → +20) vs ≈ 90+ for stairs
     * twelve cells behind (the hop wins) and ≈ 38.54 for stairs one cell
     * off-line (the walk wins) — a walking descent is preferred whenever it is
     * comparably close, and a long detour loses to a survivable drop.
     */
    public static final double DAMAGE_PENALTY_TICKS = 10.0;

    /**
     * Clearance surcharge scale, in tick units: half a sprinted block. With the
     * ring-1 fraction 0.6 this prices a wall-adjacent cell at +1.0691 ticks:
     * beside a 7-cell wall the hug lane pays 9 × 1.0691 = 9.62 while the
     * one-lane-out route pays only two extra diagonals (+2.9524) and no
     * surcharge — open travel bows a one-cell berth around obstacles, while a
     * 1-wide corridor (every cell +1.0691, no alternative) stays routable
     * (finite, additive to {@code g}; the heuristic is untouched and stays an
     * under-estimate).
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
     * The fall envelope of one plan: the deepest edge it may drop ({@code maxFall},
     * blocks), the safe-fall distance ({@code safeFall}, blocks — Jump Boost and the
     * 1.20.5+ attribute included upstream), and the post-EPF {@code damageFactor}
     * ({@link FallDamage#damageFactor}; 0 under Slow Falling). {@link #DEFAULT}
     * reproduces the classic zero-damage planner exactly: drops cap at
     * {@link #MAX_FALL} = safe fall = 3, so {@link #damagePoints} is always 0.
     */
    public record FallBudget(double maxFall, double safeFall, double damageFactor) {

        public static final FallBudget DEFAULT = new FallBudget(MAX_FALL, 3.0, 1.0);

        /** Predicted damage points of a {@code drop}-block walk-off edge. */
        public double damagePoints(double drop) {
            return Math.max(0.0, Math.ceil(drop - safeFall)) * damageFactor;
        }
    }

    /**
     * A self-describing planning result: the waypoint breadcrumb, whether the search
     * actually REACHED the (clamped) goal cell ({@code complete}), the floor Y the
     * route's final cell stands on ({@code endFloorY}), the start cell centre at its
     * floor ({@code origin} — the follower's segment [origin → waypoint 0] classifies
     * and consumes waypoint 0 like any later segment), and the end node's exact
     * {@code g} in ticks ({@code cost} — pinned by the calibration tests).
     */
    public record Route(@NotNull List<Vec3d> waypoints, boolean complete, double endFloorY,
            @NotNull Vec3d origin, double cost) {}

    /**
     * The legacy list seam ({@link LocalPathPlanner}-shaped): the waypoints of
     * {@link #plan} at the default {@link #MAX_EXTENT}, complete or partial alike.
     */
    public @NotNull Optional<List<Vec3d>> route(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget, boolean allowJump) {
        return plan(start, goal, world, budget, allowJump, MAX_EXTENT).map(Route::waypoints);
    }

    /**
     * Synchronous plan with the classic zero-damage fall envelope — one call, the
     * whole {@code budget}. See {@link #begin}/{@link #step} for the sliced seam.
     */
    public @NotNull Optional<Route> plan(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget, boolean allowJump, int extent) {
        return plan(start, goal, world, budget, allowJump, extent, FallBudget.DEFAULT);
    }

    /** Synchronous plan with an explicit fall envelope. */
    public @NotNull Optional<Route> plan(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget, boolean allowJump, int extent,
            @NotNull FallBudget fall) {
        SearchState state = begin(start, goal, world, allowJump, extent, fall, budget);
        return step(state, world, budget);
    }

    /**
     * Opens a resumable search from {@code start} toward {@code goal}, clamped to an
     * {@code extent}-cell box (capped at {@link #MAX_EXTENT_CAP}) and a vertical band of
     * {@link #Y_BAND} up / {@code max(Y_BAND, ceil(fall.maxFall()) + 2)} down — the
     * downward stretch is what makes a within-budget drop representable (the old fixed
     * band rejected every node more than 8 below the start), and it costs nothing:
     * FALL edges land directly without widening the searched space, and readability
     * still gates every cell. When {@code allowJump} is false the search refuses
     * {@code ASCEND} edges (routes around a step-up instead of over it) — stairwise
     * sub-step climbs are TRAVERSE and stay legal. {@code budget} is the TOTAL
     * expansion allowance across all slices.
     */
    public @NotNull SearchState begin(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, boolean allowJump, int extent,
            @NotNull FallBudget fall, int budget) {
        SearchState s = new SearchState();
        s.allowJump = allowJump;
        s.fall = fall;
        s.budget = budget;
        s.maxExtent = clamp(extent, 1, MAX_EXTENT_CAP);
        s.bandDown = Math.max(Y_BAND, (int) Math.ceil(fall.maxFall()) + 2);
        s.sx = floor(start.x());
        s.sz = floor(start.z());

        double startGround = NavGeometry.groundHeight(world, s.sx + 0.5, s.sz + 0.5, start.y());
        if (Double.isNaN(startGround)) {
            startGround = start.y(); // no ground read (void/edge) — anchor on the raw feet
        }
        s.sy = floor(startGround);
        s.origin = new Vec3d(s.sx + 0.5, startGround, s.sz + 0.5);

        // Clamp the goal into the box + band so a far/raised target still yields a breadcrumb.
        s.rawGoalX = floor(goal.x());
        s.rawGoalZ = floor(goal.z());
        s.gx = clamp(s.rawGoalX, s.sx - s.maxExtent, s.sx + s.maxExtent);
        s.gz = clamp(s.rawGoalZ, s.sz - s.maxExtent, s.sz + s.maxExtent);
        double goalGround = NavGeometry.groundHeight(world, s.gx + 0.5, s.gz + 0.5, goal.y());
        s.gy = clamp(Double.isNaN(goalGround) ? floor(goal.y()) : floor(goalGround),
                s.sy - s.bandDown, s.sy + Y_BAND);

        long startKey = key(s.sx, s.sy, s.sz);
        s.goalKey = key(s.gx, s.gy, s.gz);

        if (startKey == s.goalKey) {
            s.done = true;
            s.result = Optional.of(new Route(
                    List.of(new Vec3d(s.gx + 0.5, startGround, s.gz + 0.5)), true,
                    startGround, s.origin, 0.0));
            return s;
        }

        Node startNode = new Node(s.sx, s.sy, s.sz, startGround);
        s.nodes.put(startKey, startNode);
        s.gScore.put(startKey, 0.0);
        s.startKey = startKey;
        s.bestKey = startKey;
        s.bestH = heuristic(s.sx, s.sy, s.sz, s.gx, s.gy, s.gz);
        s.open.add(new Frontier(startKey, s.bestH, s.seq++));
        return s;
    }

    /**
     * Advances {@code s} by at most {@code maxExpansions} node expansions (and never
     * past its total budget). Returns {@link Optional#empty()} while the search is
     * still in flight — check {@link SearchState#done()}: once true, the SAME value is
     * the final verdict (a complete route, an anytime partial, or empty when nothing
     * better than the start cell was reachable), and further calls just return it.
     */
    public @NotNull Optional<Route> step(@NotNull SearchState s, @NotNull CollisionView world,
            int maxExpansions) {
        if (s.done) {
            return s.result;
        }
        int sliceEnd = Math.min(s.budget, s.expansions + maxExpansions);
        while (!s.open.isEmpty()) {
            if (s.expansions >= sliceEnd && s.expansions < s.budget) {
                return Optional.empty(); // slice spent — resume next tick (a pure pause)
            }
            Frontier current = s.open.poll();
            long curKey = current.key;
            if (!s.closed.add(curKey)) {
                continue; // a stale duplicate left over from a decrease-key
            }
            if (curKey == s.goalKey) {
                s.done = true;
                s.result = Optional.of(new Route(reconstruct(s.nodes, s.cameFrom, curKey),
                        true, s.nodes.get(curKey).floor, s.origin, s.gScore.get(curKey)));
                return s.result;
            }
            if (s.expansions >= s.budget) {
                // Total budget spent — but only after the popped node's goal
                // check: a search that reaches the goal on its very last pop
                // still completes. The boundary node stays closed (unexpanded,
                // uncounted), so the anytime partial scan never selects it.
                break;
            }
            s.expansions++;
            expand(s, world, curKey);
        }
        s.done = true;
        s.result = partial(s);
        return s.result;
    }

    /** One node expansion: resolve every legal neighbour edge and relax it. */
    private static void expand(SearchState s, CollisionView world, long curKey) {
        Node cur = s.nodes.get(curKey);
        double curG = s.gScore.get(curKey);

        for (int[] step : NEIGHBOURS) {
            int dx = step[0];
            int dz = step[1];
            int nx = cur.x + dx;
            int nz = cur.z + dz;
            if (Math.abs(nx - s.sx) > s.maxExtent || Math.abs(nz - s.sz) > s.maxExtent) {
                continue; // outside the bounded (region-safe) search box
            }
            // Folia gate: the neighbour column (shallow fall column + head clearance) must
            // be readable before we probe its geometry — an unreadable cell is a wall (no
            // edge). Deep-drop scans below re-gate their own longer column per block.
            if (!columnReadable(world, nx, nz, cur.y - MAX_FALL - 1, cur.y + 2)) {
                continue;
            }
            boolean diagonal = dx != 0 && dz != 0;

            double nGround = NavGeometry.groundHeight(world, nx + 0.5, nz + 0.5, cur.floor);
            if (Double.isNaN(nGround) && !diagonal && s.fall.maxFall() > MAX_FALL) {
                // Deeper than the shallow window sees: scan down the drop budget for
                // the first standable surface — the deliberate-descent edge.
                nGround = NavGeometry.deepGroundHeight(world, nx + 0.5, nz + 0.5,
                        cur.floor, s.fall.maxFall());
            }
            if (Double.isNaN(nGround)) {
                continue; // void / nothing standable within the reachable window
            }
            int ny = floor(nGround);
            if (ny - s.sy > Y_BAND || s.sy - ny > s.bandDown) {
                continue; // outside the vertical band
            }
            if (NavGeometry.collides(world, NavGeometry.playerBox(nx + 0.5, nGround, nz + 0.5))) {
                continue; // a block occupies the body here (wall / missing headroom)
            }

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
                } else if (!s.allowJump || diagonal || dy > MAX_JUMP_RISE + EPS) {
                    continue;
                } else {
                    // A climb: needs a jump, cardinal only, within a single-block rise.
                    type = MovementType.ASCEND;
                    edge = MoveCosts.SPRINT_ONE_BLOCK + MoveCosts.JUMP_ONE_BLOCK;
                }
            } else if (dy < -STEP_HEIGHT - EPS) {
                // A drop: cardinal only, within the plan's fall budget.
                double drop = -dy;
                if (diagonal || drop > s.fall.maxFall() + EPS) {
                    continue;
                }
                if (drop <= 1.0 + EPS) {
                    type = MovementType.DESCEND;
                    edge = MoveCosts.SPRINT_ONE_BLOCK + MoveCosts.CENTER_AFTER_FALL;
                } else {
                    // A deliberate walk-off: travel ticks plus the damage the landing
                    // is predicted to cost — a comparably-close walking descent stays
                    // cheaper; a long detour loses to a survivable hop.
                    type = MovementType.FALL;
                    edge = MoveCosts.WALK_OFF_BLOCK + MoveCosts.fallTicks(drop)
                            + MoveCosts.CENTER_AFTER_FALL
                            + DAMAGE_PENALTY_TICKS * s.fall.damagePoints(drop);
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
            if (s.closed.contains(nKey)) {
                continue;
            }
            // Soft clearance surcharge (memoized): a finite, ≥0 addition to g only,
            // so h stays an under-estimate and corridors never close.
            Double berth = s.clearance.get(nKey);
            if (berth == null) {
                berth = CLEARANCE_COST * NavGeometry.clearanceFraction(world, nx, nz, nGround);
                s.clearance.put(nKey, berth);
            }
            double tentative = curG + edge + berth;
            Double known = s.gScore.get(nKey);
            if (known == null || tentative < known - EPS) {
                s.gScore.put(nKey, tentative);
                s.nodes.put(nKey, new Node(nx, ny, nz, nGround));
                s.cameFrom.put(nKey, curKey);
                double h = heuristic(nx, ny, nz, s.gx, s.gy, s.gz);
                s.open.add(new Frontier(nKey, tentative + h, s.seq++));
                if (h < s.bestH - EPS) {
                    s.bestH = h;
                    s.bestKey = nKey;
                }
            }
        }
    }

    /**
     * Anytime fallback: prefer the min-h node still on the OPEN frontier — where the
     * search could have kept going (e.g. toward the stairs) — over a closed dead end;
     * the heuristic's cheap vertical term otherwise parks every failed partial on the
     * cell directly under an elevated goal. Strict minimum with a total key tie-break
     * keeps this deterministic regardless of heap iteration order. Fall back to the
     * globally best node when the frontier is empty, and keep the ≥2-cell anti-jitter bar.
     */
    private static Optional<Route> partial(SearchState s) {
        long partialKey = s.startKey;
        double partialH = Double.MAX_VALUE;
        for (Frontier f : s.open) {
            if (s.closed.contains(f.key)) {
                continue; // a stale duplicate of an already-expanded node
            }
            Node n = s.nodes.get(f.key);
            double h = heuristic(n.x, n.y, n.z, s.gx, s.gy, s.gz);
            if (h < partialH - EPS || (h < partialH + EPS && f.key < partialKey)) {
                partialH = h;
                partialKey = f.key;
            }
        }
        if (partialKey == s.startKey) {
            partialKey = s.bestKey;
        }
        if (partialKey != s.startKey) {
            Node best = s.nodes.get(partialKey);
            int advanced = Math.max(Math.abs(best.x - s.sx), Math.abs(best.z - s.sz));
            if (advanced >= 2) {
                return Optional.of(new Route(reconstruct(s.nodes, s.cameFrom, partialKey),
                        false, best.floor, s.origin, s.gScore.get(partialKey)));
            }
        }
        return Optional.empty();
    }

    /**
     * One in-flight (or finished) search: every A* local hoisted into a parkable
     * object so {@link #step} can pause at a slice boundary and resume next tick.
     * Owning-thread only, like the {@code BrainMemory} it lives in.
     */
    public static final class SearchState {
        final Map<Long, Double> gScore = new HashMap<>();
        final Map<Long, Node> nodes = new HashMap<>();
        final Map<Long, Long> cameFrom = new HashMap<>();
        final Map<Long, Double> clearance = new HashMap<>();
        final Set<Long> closed = new HashSet<>();
        final PriorityQueue<Frontier> open = new PriorityQueue<>((a, b) -> {
            int byF = Double.compare(a.f, b.f);
            return byF != 0 ? byF : Long.compare(a.seq, b.seq); // deterministic FIFO tie-break
        });
        FallBudget fall = FallBudget.DEFAULT;
        boolean allowJump;
        int budget;
        int maxExtent;
        int bandDown;
        int sx;
        int sy;
        int sz;
        int gx;
        int gy;
        int gz;
        int rawGoalX;
        int rawGoalZ;
        long startKey;
        long goalKey;
        long seq;
        long bestKey;
        double bestH;
        Vec3d origin = Vec3d.ZERO;
        int expansions;
        boolean done;
        Optional<Route> result = Optional.empty();

        /** True once the search has finished (its {@code step} value is final). */
        public boolean done() {
            return done;
        }

        /** Total node expansions spent so far (monotonic across slices). */
        public int expansions() {
            return expansions;
        }

        /**
         * Chebyshev cell distance between the live target cell and the goal this
         * search was aimed at — the caller's staleness measure.
         */
        public int goalDriftCells(int cellX, int cellZ) {
            return Math.max(Math.abs(cellX - rawGoalX), Math.abs(cellZ - rawGoalZ));
        }
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

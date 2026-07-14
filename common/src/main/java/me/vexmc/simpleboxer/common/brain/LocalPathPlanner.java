package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * A bounded voxel A* that finds a short walkable route around obstacles
 * (pillars, short walls) for the moments reactive steering is trapped — it keeps
 * grinding into a corner because "jump when blocked" can never clear a two-tall
 * wall. The planner only kicks in as a fallback, so it stays cheap and, crucially,
 * <b>Folia-safe</b>: the whole search is clamped to a {@link #MAX_EXTENT}-block box
 * around the start, so it can never probe a cell outside the calling region.
 *
 * <p>The graph is one node per horizontal block cell {@code (x,z)}; a cell's floor
 * Y is derived from {@link NavGeometry#groundHeight} so the path follows terrain
 * instead of assuming a flat plane. An edge to one of the 8 neighbours exists only
 * when that neighbour is <em>standable</em>: it has ground within a jump of the
 * current cell and the standing player box fits there (no wall, headroom clear).
 * A full-block rise reads as a {@code JUMP} edge (costed a little extra); a wall
 * too tall to clear, or a drop into the void, is simply no edge. Diagonal moves
 * that would squeeze between two solid corners are forbidden.
 */
public final class LocalPathPlanner {

    /** Half-width of the search box, in block cells, measured from the start cell. */
    public static final int MAX_EXTENT = 10;

    /** Extra cost for an edge that needs a momentum jump — nudges A* toward flat routes. */
    private static final double JUMP_COST = 0.75;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static final double EPS = 1.0E-6;

    /**
     * Plans a short route from {@code start} toward {@code goal} around obstacles,
     * clamped to a {@link #MAX_EXTENT} box and capped at {@code budget} node
     * expansions. Returns the waypoint centres from just-after-start to the (possibly
     * clamped) goal, or {@link Optional#empty()} when no route is reachable within the
     * budget and bounds. Collinear runs are collapsed, so the result is a short
     * breadcrumb of turn points, each {@code (cellX + 0.5, floorY, cellZ + 0.5)}.
     */
    public @NotNull Optional<List<Vec3d>> route(@NotNull Vec3d start, @NotNull Vec3d goal,
            @NotNull CollisionView world, int budget) {
        int sx = (int) Math.floor(start.x());
        int sz = (int) Math.floor(start.z());

        // Anchor the search on the surface actually under the start, not the raw feet Y
        // (the boxer may be mid-hop or a hair above the block it rests on).
        double startGround = NavGeometry.groundHeight(world, sx + 0.5, sz + 0.5, start.y());
        if (Double.isNaN(startGround)) {
            startGround = start.y(); // no ground read (void/edge) — fall back to feet
        }

        // Clamp the goal cell into the bounded box so a far-away target still yields a
        // reachable breadcrumb toward it (and the search never escapes the region).
        int gx = clamp((int) Math.floor(goal.x()), sx - MAX_EXTENT, sx + MAX_EXTENT);
        int gz = clamp((int) Math.floor(goal.z()), sz - MAX_EXTENT, sz + MAX_EXTENT);

        long startKey = key(sx, sz);
        long goalKey = key(gx, gz);

        if (startKey == goalKey) {
            // Already standing on the goal cell — a single waypoint keeps callers simple.
            return Optional.of(List.of(new Vec3d(gx + 0.5, startGround, gz + 0.5)));
        }

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Double> floorY = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Frontier> open =
                new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));

        gScore.put(startKey, 0.0);
        floorY.put(startKey, startGround);
        open.add(new Frontier(sx, sz, heuristic(sx, sz, gx, gz)));

        int expansions = 0;
        while (!open.isEmpty()) {
            Frontier current = open.poll();
            long curKey = key(current.x, current.z);
            if (!closed.add(curKey)) {
                continue; // a stale duplicate left over from a decrease-key
            }
            if (curKey == goalKey) {
                return Optional.of(reconstruct(cameFrom, floorY, curKey));
            }
            if (++expansions > budget) {
                return Optional.empty(); // spent the tick's budget without arriving
            }

            double curG = gScore.get(curKey);
            double curFloor = floorY.get(curKey);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = current.x + dx;
                    int nz = current.z + dz;
                    if (Math.abs(nx - sx) > MAX_EXTENT || Math.abs(nz - sz) > MAX_EXTENT) {
                        continue; // outside the bounded (region-safe) search box
                    }
                    long nKey = key(nx, nz);
                    if (closed.contains(nKey)) {
                        continue;
                    }

                    double nFloor = standFloor(world, nx, nz, curFloor);
                    if (Double.isNaN(nFloor)) {
                        continue; // wall, no floor, or drop deeper than a fall — no edge
                    }
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal && !cornerOpen(world, current.x, current.z, dx, dz, curFloor)) {
                        continue; // would squeeze diagonally between two solid corners
                    }

                    boolean jump = nFloor - curFloor > me.vexmc.simpleboxer.common.physics.ClientPhysics.STEP_HEIGHT + EPS;
                    double edge = (diagonal ? DIAGONAL : 1.0) + (jump ? JUMP_COST : 0.0);
                    double tentative = curG + edge;
                    Double best = gScore.get(nKey);
                    if (best == null || tentative < best - EPS) {
                        gScore.put(nKey, tentative);
                        floorY.put(nKey, nFloor);
                        cameFrom.put(nKey, curKey);
                        open.add(new Frontier(nx, nz, tentative + heuristic(nx, nz, gx, gz)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The floor Y a mover coming from a cell at {@code fromFloor} could stand on in
     * cell {@code (cx,cz)}, or {@code NaN} when the cell is not standable — no ground
     * within a jump/fall, or the standing player box collides with a block (wall or
     * missing headroom). {@link NavGeometry#groundHeight} already caps the reachable
     * rise at {@link NavGeometry#MAX_JUMP_RISE} above {@code fromFloor}.
     */
    private static double standFloor(@NotNull CollisionView world, int cx, int cz, double fromFloor) {
        double ground = NavGeometry.groundHeight(world, cx + 0.5, cz + 0.5, fromFloor);
        if (Double.isNaN(ground)) {
            return Double.NaN;
        }
        if (NavGeometry.collides(world, NavGeometry.playerBox(cx + 0.5, ground, cz + 0.5))) {
            return Double.NaN; // a block occupies the body here — not walkable
        }
        return ground;
    }

    /**
     * True when a diagonal step from {@code (cx,cz)} by {@code (dx,dz)} is not pinched
     * between two solid corners: at least one of the two orthogonal cells the diagonal
     * brushes past must itself be standable.
     */
    private static boolean cornerOpen(@NotNull CollisionView world, int cx, int cz,
            int dx, int dz, double fromFloor) {
        boolean sideX = !Double.isNaN(standFloor(world, cx + dx, cz, fromFloor));
        boolean sideZ = !Double.isNaN(standFloor(world, cx, cz + dz, fromFloor));
        return sideX || sideZ;
    }

    /** Octile distance — the exact 8-direction move count, admissible for our grid. */
    private static double heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int dmin = Math.min(dx, dz);
        int dmax = Math.max(dx, dz);
        return (dmax - dmin) + DIAGONAL * dmin;
    }

    /**
     * Walks {@code cameFrom} back to the start, then emits the waypoint centres from
     * just-after-start to the goal, collapsing straight collinear runs so only the
     * turn points (and floor-height changes) survive.
     */
    private static @NotNull List<Vec3d> reconstruct(@NotNull Map<Long, Long> cameFrom,
            @NotNull Map<Long, Double> floorY, long goalKey) {
        List<Long> cells = new ArrayList<>();
        long cell = goalKey;
        cells.add(cell);
        while (cameFrom.containsKey(cell)) {
            cell = cameFrom.get(cell);
            cells.add(cell);
        }
        java.util.Collections.reverse(cells); // now start -> goal

        List<Vec3d> out = new ArrayList<>();
        int n = cells.size();
        for (int i = 1; i < n; i++) { // skip index 0 (the start cell)
            boolean keep;
            if (i == n - 1) {
                keep = true; // always keep the goal
            } else {
                int pdx = Integer.compare(cx(cells.get(i)), cx(cells.get(i - 1)));
                int pdz = Integer.compare(cz(cells.get(i)), cz(cells.get(i - 1)));
                int ndx = Integer.compare(cx(cells.get(i + 1)), cx(cells.get(i)));
                int ndz = Integer.compare(cz(cells.get(i + 1)), cz(cells.get(i)));
                boolean turn = pdx != ndx || pdz != ndz;
                boolean stepped = Math.abs(floorY.get(cells.get(i))
                        - floorY.get(cells.get(i - 1))) > EPS;
                keep = turn || stepped;
            }
            if (keep) {
                long c = cells.get(i);
                out.add(new Vec3d(cx(c) + 0.5, floorY.get(c), cz(c) + 0.5));
            }
        }
        return out;
    }

    // --- cell packing (two ints into a long key) -------------------------------

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int cx(long key) {
        return (int) (key >> 32);
    }

    private static int cz(long key) {
        return (int) key;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A queued cell keyed by its A* priority {@code f = g + h}. */
    private record Frontier(int x, int z, double f) {}
}

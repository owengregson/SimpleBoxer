package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * The local obstacle-avoidance kernel — a context-steering "interest/danger map"
 * that keeps boxers from getting stuck on walls like they're sticky.
 *
 * <p>Instead of driving straight at the goal and grinding into whatever geometry
 * is in the way, we sample {@link #CANDIDATES} evenly-spaced horizontal headings,
 * score each by how much it advances the goal (interest) minus how much it walks
 * us into trouble (danger — walls, obstacles, ledges), and steer along the best
 * one. When the desired heading points straight into a wall, the winner is a
 * candidate angled ALONG the wall: that is precisely what makes the boxer slide
 * past instead of pressing into it.
 *
 * <p>Pure and deterministic: it reasons over the same {@link CollisionView} the
 * integrator collides against via {@link NavGeometry}, and uses no randomness.
 */
public final class ContextSteering {

    /** Number of candidate headings sampled evenly around the circle. */
    public static final int CANDIDATES = 16;

    /**
     * A wall too tall to jump directly ahead: must dominate any interest (which
     * caps at 1.0) so a blocked heading always loses to a clear one.
     */
    private static final double WALL_PENALTY = 10.0;
    /**
     * A tall obstacle a bit further out along a heading — small enough not to
     * override a strongly-forward clear candidate, large enough to break
     * near-ties toward the heading that stays clear the longest (early deflection).
     */
    private static final double FAR_WALL_PENALTY = 0.5;
    /** How far past {@link NavGeometry#LOOK_AHEAD} the body/head-band obstacle probe reaches. */
    private static final double FAR_LOOK_AHEAD = NavGeometry.LOOK_AHEAD + 0.6;

    /** Base cost of stepping toward a ledge; scaled by travel speed below. */
    private static final double LEDGE_PENALTY = 5.0;
    /** How far down we still consider "ground" before a drop counts as a ledge. */
    private static final double LEDGE_MAX_DROP = 3.0;
    /** A ledge is dangerous even at a crawl, but much worse at a sprint. */
    private static final double LEDGE_MIN_FACTOR = 0.3;
    /** Roughly a full sprint step (blocks/tick): normalizes the speed scaling to [0,1]. */
    private static final double REFERENCE_SPEED = 0.28;

    /** Speed cut applied when even the best heading is compromised (boxed in). */
    private static final double POOR_CANDIDATE_SPEED = 0.5;

    /**
     * The best along-wall slide must still retain at least this much of the desired
     * direction (cosine) to count as an oblique GLANCE worth hugging. A head-on wall
     * leaves only a ~perpendicular escape (cosine ~0) — below this — so it keeps the
     * classic deflect instead of grinding a near-normal candidate into the wall.
     */
    private static final double OBLIQUE_SLIDE_MIN = 0.5;
    /**
     * The graze look-ahead at full travel speed: a candidate clear for at least this
     * far only clips the wall further out, so {@code ClientPhysics.collide} slides it
     * rather than stopping it. Roughly one sprint step. At a crawl the look-ahead
     * relaxes back to {@link NavGeometry#LOOK_AHEAD} (a stopped boxer can't slide).
     */
    private static final double WALL_GRAZE_MIN_AHEAD = 0.28;

    /**
     * Steer {@code desiredDirWorld} into the cleanest nearby heading: full-speed
     * toward the goal on open ground, angled along walls when the goal points into
     * one, and easing off near ledges. Returns {@link MoveHeading#STILL} when there
     * is no meaningful desire to move.
     *
     * @param p                the per-tick snapshot (only {@code self} kinematics are read)
     * @param desiredDirWorld  the world-space direction the goal wants to travel
     * @param world            the live collision view to probe against
     * @return the collision-aware heading the motor should actually drive
     */
    public @NotNull MoveHeading steer(@NotNull Perception p, @NotNull Vec3d desiredDirWorld,
            @NotNull CollisionView world) {
        return steer(p, desiredDirWorld, world, false);
    }

    public @NotNull MoveHeading steer(@NotNull Perception p, @NotNull Vec3d desiredDirWorld,
            @NotNull CollisionView world, boolean mayLeaveLedges) {
        Vec3d desired = desiredDirWorld.horizontalNormalized();
        if (desired.lengthSqr() < 1.0E-8) {
            return MoveHeading.STILL; // no goal direction — hold position
        }

        Perception.SelfState self = p.self();
        Box box = NavGeometry.playerBox(self.x(), self.y(), self.z());
        // A ledge is only dangerous in proportion to how fast we'd sail off it —
        // unless this is a pursuit that MAY leave ledges (chasing a target off an
        // edge like a real client), in which case the drop costs nothing.
        double speedFactor = Math.min(1.0, self.velocity().horizontalLength() / REFERENCE_SPEED);
        double ledgeCost = mayLeaveLedges ? 0.0
                : LEDGE_PENALTY * (LEDGE_MIN_FACTOR + (1.0 - LEDGE_MIN_FACTOR) * speedFactor);

        Vec3d bestDir = desired;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestInterest = 0.0;
        double bestDanger = 0.0;

        for (int i = 0; i < CANDIDATES; i++) {
            double theta = (2.0 * Math.PI * i) / CANDIDATES;
            // Candidate 0 = +X (East); map angle onto the XZ plane as (cos, sin).
            Vec3d cand = new Vec3d(Math.cos(theta), 0.0, Math.sin(theta));

            double rawDot = cand.dot(desired);
            double interest = Math.max(0.0, rawDot);
            double danger = danger(world, box, cand, ledgeCost);
            double score = interest - danger;

            // Primary: best score. Tie-break: hug the heading closest to the goal
            // (raw dot), so we never reverse when a perpendicular slide scores the
            // same as backing away.
            if (score > bestScore + 1.0E-9
                    || (score > bestScore - 1.0E-9 && rawDot > bestDir.dot(desired) + 1.0E-9)) {
                bestScore = score;
                bestDir = cand;
                bestInterest = interest;
                bestDanger = danger;
            }
        }

        // Oblique wall pass-through: when the goal points into a wall but the best
        // slide still GLANCES along it (keeps most of the desired direction), prefer
        // the candidate NEAREST to desired that merely grazes the wall at speed —
        // ClientPhysics.collide finishes the slide — instead of snapping to the pure
        // along-wall perpendicular. A genuinely head-on wall (best slide ~normal to
        // desired) fails the glance test and keeps the classic deflect.
        Vec3d graze = obliqueGraze(world, box, desired, bestDir, bestDanger, speedFactor,
                mayLeaveLedges);
        if (graze != null) {
            boolean grazeLedge = !mayLeaveLedges && NavGeometry.ledgeAhead(world, box, graze,
                    NavGeometry.LOOK_AHEAD, LEDGE_MAX_DROP);
            // A grazing slide is a compromised heading: ease off so collide's slide
            // stays controlled (the motor duty-cycles the digital forward).
            return new MoveHeading(graze, grazeLedge, POOR_CANDIDATE_SPEED);
        }

        // A pursuit that may leave ledges must not then crawl-sneak off the edge:
        // suppress the near-ledge ease-off so it steps off at pace toward the target.
        boolean nearLedge = !mayLeaveLedges && NavGeometry.ledgeAhead(world, box, bestDir,
                NavGeometry.LOOK_AHEAD, LEDGE_MAX_DROP);
        // Ease off only when we're settling for a compromised heading — either
        // something still dangerous ahead, or no candidate makes real progress.
        double speedScale = (bestDanger > 1.0E-9 || bestInterest < 1.0E-6)
                ? POOR_CANDIDATE_SPEED : 1.0;
        return new MoveHeading(bestDir, nearLedge, speedScale);
    }

    /**
     * Total danger for driving {@code cand}: a heavy penalty for a wall right
     * ahead, a light one for a tall obstacle a bit further along the body/head
     * band, and a speed-scaled ledge cost.
     */
    private static double danger(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d cand, double ledgeCost) {
        double danger = 0.0;
        if (NavGeometry.wallAhead(world, box, cand, NavGeometry.LOOK_AHEAD)) {
            danger += WALL_PENALTY;
        }
        if (NavGeometry.wallAhead(world, box, cand, FAR_LOOK_AHEAD)) {
            danger += FAR_WALL_PENALTY;
        }
        if (NavGeometry.ledgeAhead(world, box, cand, NavGeometry.LOOK_AHEAD, LEDGE_MAX_DROP)) {
            danger += ledgeCost;
        }
        return danger;
    }

    /**
     * The candidate NEAREST to {@code desired} that only grazes a wall it points
     * obliquely into — or {@code null} when this is not an oblique-wall situation
     * (desired is clear, boxed in, or the best slide is near-normal to desired).
     *
     * <p>Gated so it never fires on a head-on wall: the classic best slide
     * ({@code bestDir}) must be clear AND still retain most of {@code desired}
     * (an oblique glance). Then a candidate qualifies only if it is nearer to
     * desired than {@code bestDir}, clear within the speed-scaled graze look-ahead
     * (so {@code ClientPhysics.collide} slides it rather than stopping it), and does
     * not step off a ledge (unless pursuit may).
     */
    private static Vec3d obliqueGraze(@NotNull CollisionView world, @NotNull Box box,
            @NotNull Vec3d desired, @NotNull Vec3d bestDir, double bestDanger,
            double speedFactor, boolean mayLeaveLedges) {
        boolean desiredBlocked = NavGeometry.wallAhead(world, box, desired, NavGeometry.LOOK_AHEAD);
        boolean bestGlances = bestDanger < WALL_PENALTY && bestDir.dot(desired) > OBLIQUE_SLIDE_MIN;
        if (!desiredBlocked || !bestGlances) {
            return null;
        }
        // Faster travel carries further into a graze before collision bites, so the
        // look-ahead relaxes toward WALL_GRAZE_MIN_AHEAD; a crawl keeps the full one.
        double grazeAhead = NavGeometry.LOOK_AHEAD
                - (NavGeometry.LOOK_AHEAD - WALL_GRAZE_MIN_AHEAD) * speedFactor;

        Vec3d graze = null;
        double bestDot = bestDir.dot(desired);
        for (int i = 0; i < CANDIDATES; i++) {
            double theta = (2.0 * Math.PI * i) / CANDIDATES;
            Vec3d cand = new Vec3d(Math.cos(theta), 0.0, Math.sin(theta));
            double dot = cand.dot(desired);
            if (dot <= bestDot + 1.0E-9) {
                continue; // not nearer to desired than what we already have
            }
            if (NavGeometry.wallAhead(world, box, cand, grazeAhead)) {
                continue; // a wall even at the graze reach — too head-on to slide
            }
            if (!mayLeaveLedges
                    && NavGeometry.ledgeAhead(world, box, cand, NavGeometry.LOOK_AHEAD, LEDGE_MAX_DROP)) {
                continue; // don't graze off a ledge
            }
            graze = cand;
            bestDot = dot;
        }
        return graze;
    }
}

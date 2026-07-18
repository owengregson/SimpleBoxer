package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * The straight-corridor shortcut: a march down the direct boxer→target line
 * answering ONE question — "can this line simply be walked?" — with exactly
 * the planner's own primitives (ground snapping, standing-box headroom).
 * While the answer is yes the brain neither plans nor keeps a route; steering
 * drives the line at full pace. This is the no-stall straight path: ~2
 * geometry calls per 1-block stride against the hundreds of thousands a
 * flooding A* was measured spending.
 *
 * <p>Per station the floor CHAINS from the previous station's floor: a rise
 * within the vanilla auto-step is walked; ONE rise within a running jump is
 * allowed ({@link ProactiveJump} schedules the actual takeoff when steering
 * runs the line); and a drop is allowed whenever the first standable surface
 * below sits within the caller's drop budget — the deliberate-descent rule,
 * fed by {@link FallDamage} upstream. Anything else — a second jump, a rise
 * no jump clears, a beyond-budget chasm, missing standing room, an
 * unreadable column — fails the line, and control falls back to the planner
 * gates. Pure, allocation-free, deterministic.</p>
 */
final class CorridorProbe {

    /**
     * Longest line the probe verifies, in 1-block strides. Elevated targets
     * farther out than this go to the planner; a FLAT farther target never
     * needed a plan in the first place (no gate fires without a level gap).
     */
    static final int MAX_STRIDES = 16;
    private static final double EPS = 1.0E-6;

    private CorridorProbe() {}

    /**
     * True when every 1-block station of the direct line from {@code self} to
     * {@code target} chains walkably: rises ≤ step height, at most ONE
     * jumpable rise, drops within {@code dropBudget} blocks, standing headroom
     * at every station's floor — and the chain ENDS on the target's own
     * standing level (a line that merely passes under a raised target is not
     * a corridor to it).
     */
    static boolean clear(@NotNull CollisionView world, @NotNull Vec3d self,
            @NotNull Vec3d target, double dropBudget) {
        double dx = target.x() - self.x();
        double dz = target.z() - self.z();
        double span = Math.sqrt(dx * dx + dz * dz);
        if (span < EPS) {
            return true; // standing on the target column — nothing to verify
        }
        if (span > MAX_STRIDES) {
            return false; // beyond the probe horizon — the planner gates decide
        }
        double targetGround = NavGeometry.groundHeight(world, target.x(), target.z(), target.y());
        if (Double.isNaN(targetGround)) {
            return false; // cannot verify the endpoint's own footing
        }
        double ux = dx / span;
        double uz = dz / span;
        double floor = NavGeometry.groundHeight(world, self.x(), self.z(), self.y());
        if (Double.isNaN(floor)) {
            floor = self.y(); // airborne over a lip — chain from the feet
        }
        boolean jumpSpent = false;
        int stations = (int) Math.ceil(span - EPS);
        for (int i = 1; i <= stations; i++) {
            double along = Math.min(i, span);
            double sx = self.x() + ux * along;
            double sz = self.z() + uz * along;
            double ground = NavGeometry.groundHeight(world, sx, sz, floor);
            if (Double.isNaN(ground)) {
                // Deeper than the shallow window sees: one budget-bounded deep
                // scan (per-block isReadable-gated) finds the landing or fails.
                ground = NavGeometry.deepGroundHeight(world, sx, sz, floor, dropBudget);
            }
            if (Double.isNaN(ground)) {
                return false; // void beyond the drop budget, or unreadable below
            }
            double rise = ground - floor;
            if (rise > NavGeometry.MAX_JUMP_RISE + EPS) {
                return false; // a wall no running jump clears
            }
            if (-rise > dropBudget + EPS) {
                return false; // the shallow window saw it, but it exceeds the budget
            }
            if (rise > ClientPhysics.STEP_HEIGHT + EPS) {
                if (jumpSpent) {
                    return false; // a second jump means real terrain — plan instead
                }
                jumpSpent = true;
            }
            if (NavGeometry.collides(world, NavGeometry.playerBox(sx, ground, sz))) {
                return false; // no standing room at the station (wall / low ceiling)
            }
            floor = ground;
        }
        // The chain must END at the target's standing level: the final station IS
        // the target column, but its window chains from the previous floor — a
        // raised target whose face the line cannot climb leaves the chain below.
        return Math.abs(floor - targetGround) <= ClientPhysics.STEP_HEIGHT + EPS;
    }
}

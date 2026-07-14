package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Detects a boxer that <em>wants</em> to move but is making no horizontal
 * progress — pinned on a corner, doorway, or block edge the reactive jump and
 * context steering never freed — and produces an escape.
 *
 * <p>The escalation is two-stage: first a lateral detour (slide 90° off the
 * intended heading toward whichever side is clear, wiggling between sides on
 * successive ticks), and if that still doesn't unpin the boxer after a while,
 * {@link #shouldReroute} tells the caller to throw away the local path and
 * invoke the planner for a fresh route.
 *
 * <p>Stateful across ticks via {@link BrainMemory}: the stuck-ticks counter
 * lives in {@code mem.ints("antiStuck", …)} and the detour side alternates
 * through {@code mem.strafeSign}. {@link #isStuck} is the once-per-tick driver
 * that advances (or resets) that counter, so it must be called every tick.
 */
public final class AntiStuck {

    /** Scratch id + layout for our stuck-ticks counter in {@link BrainMemory#ints}. */
    private static final String SCRATCH = "antiStuck";
    private static final int STUCK_TICKS = 0;

    /**
     * Net displacement (blocks) over the memory window below which we consider the
     * boxer to be making "no progress". Position-based, so a boxer jittering in
     * place against a wall (high per-tick speed, ~zero net travel — the classic
     * sticky-wall) reads as pinned, while one genuinely creeping along a wall
     * accumulates enough net travel to clear the bar.
     */
    private static final double NET_PROGRESS_EPSILON = 0.35;

    /** Consecutive stuck ticks before we flag it (avoids reacting to a one-tick bump). */
    private static final int STUCK_FLAG_TICKS = 3;

    /**
     * Consecutive stuck ticks after which lateral detours are deemed to have
     * failed and a full reroute is warranted. Comfortably longer than the flag
     * window so the sideways escape gets a real chance first.
     */
    private static final int STUCK_REROUTE_TICKS = 12;

    /** A detour eases off the throttle — we're feeling our way out, not charging. */
    private static final double DETOUR_SPEED_SCALE = 0.7;
    /** Backing off a fully walled corner is slower still, to peel cleanly off it. */
    private static final double BACKOFF_SPEED_SCALE = 0.5;

    /**
     * Whether the boxer intends to move yet is going nowhere: it has a foe to
     * pursue and its net horizontal displacement has been ~zero for a sustained
     * window. Deliberately does NOT require the integrator's collision flag — the
     * nastiest sticky case is a boxer that oscillates a hair short of a wall
     * (steering deflects it before contact, so it never registers a collision)
     * yet makes no net headway. Having a target is the movement-intent proxy; the
     * caller further gates escalation to when the boxer is actually trying to
     * approach (not orbit).
     *
     * <p>Side effect: advances the stuck-ticks counter this tick (or resets it
     * when a condition fails), so call this exactly once per tick.
     */
    public boolean isStuck(@NotNull Perception p, @NotNull BrainMemory mem) {
        int[] state = mem.ints(SCRATCH, 1);

        boolean intendsToMove = p.hasTarget();
        boolean noProgress = mem.netProgress() < NET_PROGRESS_EPSILON;

        if (intendsToMove && noProgress) {
            state[STUCK_TICKS]++;
        } else {
            // DECAY rather than hard-reset: a lateral detour makes brief bursts of
            // net travel that would otherwise reset the counter every few ticks, so
            // a boxer wiggling in place forever would never escalate to a reroute.
            state[STUCK_TICKS] = Math.max(0, state[STUCK_TICKS] - 1);
        }
        return state[STUCK_TICKS] >= STUCK_FLAG_TICKS;
    }

    /**
     * A sideways escape heading for a stuck boxer: try the perpendicular side
     * {@code mem.strafeSign} prefers, else the other side, choosing whichever is
     * not walled per {@link NavGeometry#wallAhead}. The preferred side flips each
     * call so the boxer wiggles both ways across successive ticks. If both sides
     * are walled the boxer is cornered, so we reverse straight back off the
     * obstacle. {@code speedScale} is reduced in every case.
     *
     * <p>Returns {@link MoveHeading#STILL} when there is no intended heading to
     * detour from.
     */
    public @NotNull MoveHeading detour(@NotNull Perception p, @NotNull MoveHeading intended,
            @NotNull CollisionView world, @NotNull BrainMemory mem) {
        Vec3d flat = intended.dirWorld().horizontalNormalized();
        if (flat.lengthSqr() < 1.0E-8) {
            return MoveHeading.STILL; // nothing to slide off of
        }

        // Own alternation state (not the strafe goal's sign, to avoid coupling
        // two unrelated routines): wiggle to the opposite side next detour.
        int[] side = mem.ints("antiStuckDetour", 1);
        if (side[0] == 0) {
            side[0] = 1;
        }
        int sign = side[0];
        side[0] = -sign;
        Vec3d preferred = perpendicular(flat, sign);
        Vec3d alternate = perpendicular(flat, -sign);

        Box box = NavGeometry.playerBox(p.self().x(), p.self().y(), p.self().z());
        if (!NavGeometry.wallAhead(world, box, preferred, NavGeometry.LOOK_AHEAD)) {
            return heading(preferred, box, world, DETOUR_SPEED_SCALE);
        }
        if (!NavGeometry.wallAhead(world, box, alternate, NavGeometry.LOOK_AHEAD)) {
            return heading(alternate, box, world, DETOUR_SPEED_SCALE);
        }

        // Cornered on both flanks — peel straight back off the obstacle.
        return heading(flat.scale(-1.0), box, world, BACKOFF_SPEED_SCALE);
    }

    /** A heading whose ledge flag is computed for the direction actually chosen. */
    private static MoveHeading heading(Vec3d dir, Box box, CollisionView world, double speedScale) {
        boolean ledge = NavGeometry.ledgeAhead(world, box, dir, NavGeometry.LOOK_AHEAD, 3.0);
        return new MoveHeading(dir, ledge, speedScale);
    }

    /**
     * True once the boxer has been stuck long enough that lateral detours have
     * demonstrably not helped — the caller should drop its cached path and run
     * the planner for a fresh route. A pure read of the counter {@link #isStuck}
     * maintains.
     */
    public boolean shouldReroute(@NotNull BrainMemory mem) {
        return mem.ints(SCRATCH, 1)[STUCK_TICKS] >= STUCK_REROUTE_TICKS;
    }

    /**
     * A unit horizontal heading 90° off {@code flat} (already unit horizontal),
     * to the left for {@code sign = +1} and to the right for {@code sign = -1}.
     * Rotation in the XZ plane: (x,z) → sign·(z, -x).
     */
    private static @NotNull Vec3d perpendicular(@NotNull Vec3d flat, int sign) {
        return new Vec3d(sign * flat.z(), 0.0, -sign * flat.x());
    }
}

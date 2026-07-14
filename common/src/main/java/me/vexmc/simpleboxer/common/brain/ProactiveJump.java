package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Decides the "bunny-hop up a one-block step" jump — the fix for boxers that
 * grind to a halt against a raised block instead of climbing it. The vanilla
 * auto-step only clears rises up to {@link me.vexmc.simpleboxer.common.physics.ClientPhysics#STEP_HEIGHT};
 * anything taller (a full block) needs a real jump, and it has to leave the
 * ground <em>before</em> the player box touches the step face — once the boxer
 * has collided, horizontal velocity is already zeroed and a reactive jump goes
 * straight up into the wall with no momentum to carry it over.
 *
 * <p>So we probe a little farther ahead than the steering look-ahead and raise
 * the jump while momentum is still intact. A tall wall (taller than a jump) is
 * deliberately <em>not</em> jumped — that needs a detour from the planner, not a
 * hop — and we never hop toward a drop-off. A short {@code mem}-backed cooldown
 * keeps the boxer from spam-hopping in place against a step it hasn't cleared yet.
 */
public final class ProactiveJump {

    /** Scratch id for the anti-spam cooldown bookkeeping. */
    private static final String MEM_ID = "proactiveJump";
    /**
     * Look-ahead for the proactive probe, wider than {@link NavGeometry#LOOK_AHEAD}
     * (0.55) so the jump fires a fraction of a block before the step face — that
     * lead is what lets horizontal momentum carry the boxer up and over.
     */
    private static final double PROACTIVE_LOOK_AHEAD = 0.75;
    /** How far below the probe counts as "still ground" when rejecting ledges. */
    private static final double LEDGE_MAX_DROP = 2.0;
    /** Ticks to suppress re-jumping after a hop, so one step gets one hop, not a stutter. */
    private static final int COOLDOWN_TICKS = 3;

    /**
     * Whether the motor should press jump this tick to climb a step in front of
     * the boxer's heading.
     *
     * @param p       the current perception snapshot
     * @param heading the collision-aware world-space heading the boxer is steering
     * @param world   the collision view to probe geometry against
     * @param mem     the owning-thread scratchpad (holds the cooldown clock)
     * @return {@link JumpHint#JUMP} to hop this tick, else {@link JumpHint#NONE}
     */
    public @NotNull JumpHint evaluate(@NotNull Perception p, @NotNull MoveHeading heading,
            @NotNull CollisionView world, @NotNull BrainMemory mem) {
        // Airborne: nothing to push off of — a jump impulse does nothing useful.
        if (!p.self().onGround()) {
            return JumpHint.NONE;
        }
        // No heading means no direction to probe, and nothing to carry us over a
        // step. (A boxer pinned head-on against a step is still steering INTO it,
        // so its heading is non-still even when its velocity has been zeroed.)
        if (heading.isStill()) {
            return JumpHint.NONE;
        }

        // Anti-spam: slot 0 holds the server tick until which hopping is muted.
        int[] cd = mem.ints(MEM_ID, 1);
        int now = (int) p.combat().serverTick();
        if (now < cd[0]) {
            return JumpHint.NONE;
        }

        Vec3d dir = heading.dirWorld();
        Box box = NavGeometry.playerBox(p.self().x(), p.self().y(), p.self().z());

        // A wall taller than a jump is a detour problem, not a hop — bail so the
        // planner can route around it instead of us bouncing off its face.
        if (NavGeometry.wallAhead(world, box, dir, PROACTIVE_LOOK_AHEAD)) {
            return JumpHint.NONE;
        }
        // Never launch toward a drop-off. (A real step collides at the probe, so
        // this only trips on genuine edges, leaving legitimate hops untouched.)
        if (NavGeometry.ledgeAhead(world, box, dir, PROACTIVE_LOOK_AHEAD, LEDGE_MAX_DROP)) {
            return JumpHint.NONE;
        }

        // Primary: a climbable block (rise in (STEP_HEIGHT, MAX_JUMP_RISE]) is
        // coming up within the widened probe — jump now, before contact.
        boolean stepAhead = NavGeometry.needsJumpAhead(world, box, dir, PROACTIVE_LOOK_AHEAD);
        // Fallback: already pressed into a climbable step head-on at rest (the
        // proactive lead missed because we started from a standstill against it).
        boolean pinnedOnStep = p.self().horizontalCollision()
                && NavGeometry.needsJumpAhead(world, box, dir, NavGeometry.LOOK_AHEAD);

        if (stepAhead || pinnedOnStep) {
            cd[0] = now + COOLDOWN_TICKS;
            return JumpHint.JUMP;
        }
        return JumpHint.NONE;
    }
}

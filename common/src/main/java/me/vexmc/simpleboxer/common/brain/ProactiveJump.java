package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Decides the "bunny-hop up a one-block step" jump — the fix for boxers that
 * grind to a halt against a raised block instead of climbing it. The vanilla
 * auto-step only clears rises up to {@link ClientPhysics#STEP_HEIGHT}; anything
 * taller (a full block) needs a real jump, and it has to leave the ground so
 * that the box reaches the step face exactly on the air tick the feet clear it:
 * the integrator grants step-up only when {@code (onGround || landing)} — never
 * mid-rise — and zeroes horizontal velocity on contact, so a jump raised too
 * late (or too early) degenerates into a face-press.
 *
 * <p>The trigger is therefore a takeoff-window test in TICKS, from the
 * integrator's own arithmetic: the face distance comes from
 * {@link NavGeometry#stepFaceAhead} (exact contact distance and rise, not a
 * fixed-horizon guess), per-tick displacements are predicted from the aged
 * movement-speed attribute, the current velocity and the launch-tick ground
 * drag, and the jump arc (with Jump Boost) names the air tick {@code k} whose
 * end clears the rise. Fire exactly when contact would land on tick {@code k}
 * — or one window early when the next grounded stride would skip the window
 * entirely (Speed II+ strides outrun the window width; crossing on tick
 * {@code k+1} is still feet-above-rise). A tall wall (taller than a jump) is
 * deliberately <em>not</em> jumped — that needs a detour from the planner — and
 * we never hop when the landing past the lip is a chasm. A short {@code mem}
 * countdown keeps the boxer from spam-hopping against a step it hasn't cleared
 * yet, and the route follower's scheduled-takeoff cue
 * ({@link BrainMemory#routeStepFace}) fills in when the geometric probe misses
 * (diagonal headings, odd geometry) so planned ASCEND edges become scheduled
 * takeoffs with momentum intact.
 */
public final class ProactiveJump {

    /** Scratch id for the anti-spam cooldown bookkeeping. */
    private static final String MEM_ID = "proactiveJump";
    /**
     * How far ahead (blocks of box travel) the face probe scans. Covers the widest
     * takeoff window in the matrix: Speed II sprint fires out to S₂ + stride
     * ≈ 1.335 blocks (see the window table in the tests).
     */
    private static final double TAKEOFF_HORIZON = 1.6;
    /** How far below the landing probe counts as "still ground" when rejecting chasms. */
    private static final double LEDGE_MAX_DROP = 2.0;
    /** Evaluate-calls (one per decision tick) to suppress re-jumping after a hop. */
    private static final int COOLDOWN_TICKS = 3;
    /**
     * Latest air tick a crossing may land on: feet stay above one block through
     * air tick 8 (1.0244) and drop below on tick 9 (0.7967), so later crossings
     * cannot clear a full-block rise anyway.
     */
    private static final int MAX_CLEAR_TICKS = 8;

    /**
     * Whether the motor should press jump this tick to climb a step in front of
     * the boxer's heading.
     *
     * @param p         the current perception snapshot
     * @param heading   the collision-aware world-space heading the boxer is steering
     * @param sprinting whether the motor will hold sprint (adds the 0.2 takeoff push
     *                  and selects the sprint air/ground acceleration)
     * @param world     the collision view to probe geometry against
     * @param mem       the owning-thread scratchpad (holds the cooldown countdown and
     *                  the route follower's scheduled-takeoff cue)
     * @return {@link JumpHint#JUMP} to hop this tick, else {@link JumpHint#NONE}
     */
    public @NotNull JumpHint evaluate(@NotNull Perception p, @NotNull MoveHeading heading,
            boolean sprinting, @NotNull CollisionView world, @NotNull BrainMemory mem) {
        // Anti-spam countdown: burns one unit per decision tick (airtime included,
        // so a landing may re-hop at once — consecutive stair risers need it).
        int[] cd = mem.ints(MEM_ID, 1);
        if (cd[0] > 0) {
            cd[0]--;
            return JumpHint.NONE;
        }
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

        Perception.SelfState self = p.self();
        Vec3d dir = heading.dirWorld().horizontalNormalized();
        Box box = NavGeometry.playerBox(self.x(), self.y(), self.z());

        // The face: geometric probe first; the route follower's scheduled ASCEND
        // cue fills in when the probe misses.
        double faceDistance;
        double rise;
        NavGeometry.StepFace face = NavGeometry.stepFaceAhead(world, box, dir, TAKEOFF_HORIZON);
        if (face != null) {
            faceDistance = face.distance();
            rise = face.rise();
        } else if (!Double.isNaN(mem.routeStepFace) && mem.routeStepFace <= TAKEOFF_HORIZON) {
            faceDistance = mem.routeStepFace;
            rise = mem.routeStepRise;
        } else {
            return JumpHint.NONE;
        }
        if (rise <= ClientPhysics.STEP_HEIGHT + 1.0E-6) {
            return JumpHint.NONE; // the vanilla auto-step clears it — no hop needed
        }
        if (rise > NavGeometry.MAX_JUMP_RISE + 1.0E-6) {
            return JumpHint.NONE; // a wall taller than a jump is a detour problem (planner)
        }
        // Never hop when the cell past the lip is a chasm: probe one block beyond
        // the face — a solid step body reads as ground and passes.
        if (NavGeometry.ledgeAhead(world, box, dir, faceDistance + 1.0, LEDGE_MAX_DROP)) {
            return JumpHint.NONE;
        }

        // Takeoff-window arithmetic, exactly ClientPhysics.step's order: the launch
        // tick ships velocity + push + ground accel and then decays at GROUND drag
        // (slip is read pre-move); airborne ticks decay at air drag and add air
        // accel; the Y arc (with Jump Boost) names the clearing tick k.
        double slip = world.slipperiness(floor(self.x()), floor(self.y() - 0.5000001),
                floor(self.z()));
        double groundAccel = ClientPhysics.INPUT_SCALE
                * self.movementSpeed() * (sprinting ? ClientPhysics.SPRINT_SPEED_MULTIPLIER : 1.0)
                * (ClientPhysics.GROUND_ACCEL_MAGIC / (slip * slip * slip));
        double airAccel = ClientPhysics.INPUT_SCALE
                * (sprinting ? ClientPhysics.SPRINT_AIR_ACCEL : ClientPhysics.WALK_AIR_ACCEL);
        Vec3d velocity = self.velocity();
        double vAlong = Math.max(0.0, velocity.x() * dir.x() + velocity.z() * dir.z());
        double dTick = vAlong + groundAccel
                + (sprinting ? ClientPhysics.SPRINT_JUMP_PUSH : 0.0);
        double vy = ClientPhysics.DEFAULT_JUMP_STRENGTH
                + (self.jumpBoostAmplifier() >= 0 ? 0.1 * (self.jumpBoostAmplifier() + 1) : 0.0);

        double feet = 0.0;
        double travelledBefore = 0.0;  // S_{k-1}: travel while the face is still solid
        double travelledThrough = 0.0; // S_k: travel through the tick the feet clear
        boolean clears = false;
        for (int tick = 1; tick <= MAX_CLEAR_TICKS; tick++) {
            feet += vy;
            vy = (vy - ClientPhysics.GRAVITY) * ClientPhysics.VERTICAL_DRAG;
            travelledThrough = travelledBefore + dTick;
            if (feet >= rise - 1.0E-9) {
                clears = true;
                break;
            }
            travelledBefore = travelledThrough;
            dTick = dTick * (tick == 1 ? slip * ClientPhysics.AIR_DRAG : ClientPhysics.AIR_DRAG)
                    + airAccel;
        }

        // Fire when contact lands exactly on the clearing tick — or one window early
        // when the next grounded stride would skip the window entirely.
        double stride = vAlong + groundAccel;
        boolean inWindow = clears
                && faceDistance > travelledBefore + 1.0E-9
                && (faceDistance <= travelledThrough + 1.0E-9
                        || faceDistance - stride <= travelledBefore + 1.0E-9);
        // Fallback: already pressed into a climbable step head-on at rest (the
        // takeoff lead missed because we started from a standstill against it).
        boolean pinnedOnStep = self.horizontalCollision()
                && NavGeometry.needsJumpAhead(world, box, dir, NavGeometry.LOOK_AHEAD);

        if (inWindow || pinnedOnStep) {
            cd[0] = COOLDOWN_TICKS;
            return JumpHint.JUMP;
        }
        return JumpHint.NONE;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}

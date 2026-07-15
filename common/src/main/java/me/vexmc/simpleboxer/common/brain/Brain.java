package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.vexmc.simpleboxer.common.aim.AimSpring;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.brain.Intent.FacingIntent;
import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.brain.goal.EngageGoal;
import me.vexmc.simpleboxer.common.brain.goal.IdleGoal;
import me.vexmc.simpleboxer.common.brain.goal.PotHealGoal;
import me.vexmc.simpleboxer.common.brain.goal.RodPokeGoal;
import me.vexmc.simpleboxer.common.brain.goal.SeekFoodGoal;
import me.vexmc.simpleboxer.common.brain.goal.StandGoal;
import me.vexmc.simpleboxer.common.combat.ClickScheduler;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.physics.MoveInput;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * The whole boxer brain behind one entry point — the utility arbiter, the aim
 * spring, the motor stack (context steering → proactive jump → anti-stuck →
 * bounded path planner → digital quantizer), and the click controller — reasoning
 * over an immutable {@link Perception} and returning a {@link BrainOutput} the
 * {@code BoxerImpl} adapter applies at exactly three seams (move input, aim
 * angles, action list). Pure and owning-thread; deterministic from the seed.
 */
public final class Brain {

    private static final int PLAN_BUDGET = 400;
    private static final double WAYPOINT_REACHED_SQ = 1.0;

    private volatile BoxerSettings settings;

    private final AimSpring aim;
    private final ClickScheduler clicker;
    private final BrainMemory memory;
    private final Arbiter arbiter;

    private final AdaptiveStrafe adaptiveStrafe = new AdaptiveStrafe();
    private final ContextSteering steering = new ContextSteering();
    private final ProactiveJump proactiveJump = new ProactiveJump();
    private final AntiStuck antiStuck = new AntiStuck();
    // Baritone-style 3D voxel A* (traverse/diagonal/ascend/descend/fall) — same
    // route() seam as the retired LocalPathPlanner, but it finds elevation routes
    // (stairs/step-ups off the direct line) and degrades to an anytime partial.
    private final BaritoneStylePlanner planner = new BaritoneStylePlanner();
    private final MotorQuantizer motor = new MotorQuantizer();
    private final ClickController clicks = new ClickController();
    private final BlockhitController blockhit = new BlockhitController();

    public Brain(@NotNull BoxerSettings settings, long seed, float initialYaw, float initialPitch) {
        this.settings = settings;
        this.aim = new AimSpring(settings.aim(), initialYaw, initialPitch);
        this.clicker = new ClickScheduler(settings.cps(), settings.clickJitter(), seed);
        this.memory = new BrainMemory(seed);
        List<Goal> goals = new ArrayList<>();
        // Survival/technique routines score ABOVE ordinary engagement when their
        // situation fires (low health, hunger, a rod-pokeable approach) and pre-empt it.
        goals.add(new PotHealGoal(this::settings));
        goals.add(new SeekFoodGoal(this::settings));
        goals.add(new RodPokeGoal(this::settings));
        goals.add(new EngageGoal(this::settings, adaptiveStrafe));
        goals.add(new StandGoal(this::settings));
        goals.add(new IdleGoal());
        this.arbiter = new Arbiter(goals);
    }

    public @NotNull BoxerSettings settings() {
        return settings;
    }

    /** Owning-thread only: republish the profile and re-tune the aim spring + clicker. */
    public void retune(@NotNull BoxerSettings newSettings) {
        this.settings = newSettings;
        aim.retune(newSettings.aim());
        clicker.retune(newSettings.cps(), newSettings.clickJitter());
    }

    /** Hard-snap the crosshair (teleport/respawn resync). */
    public void snapAim(float yaw, float pitch) {
        aim.snapTo(yaw, pitch);
    }

    public float aimYaw() {
        return aim.yaw();
    }

    public float aimPitch() {
        return aim.pitch();
    }

    public @NotNull BrainMemory memory() {
        return memory;
    }

    /**
     * A hit this boxer threw landed — arm the w-tap / s-tap sprint-reset countdown
     * (the release then re-press that re-arms sprint-extra knockback).
     */
    public void onHitLanded() {
        BoxerSettings s = settings;
        boolean reset = s.wtap().enabled() || s.combat().sTap();
        if (reset && memory.wtapReleaseLeft == 0 && memory.wtapCountdown < 0) {
            memory.wtapCountdown = s.wtap().delayTicks();
        }
    }

    /** One decision tick over the delayed perception + live collision geometry. */
    public @NotNull BrainOutput tick(@NotNull Perception p, @NotNull CollisionView world, long nowMs) {
        BoxerSettings s = settings;
        // Progress bookkeeping for stall detection: the sim's current position, so
        // anti-stuck can tell "jittering in place" from "creeping along a wall".
        memory.recordPosition(p.self().x(), p.self().z());

        Arbiter.Result decision = arbiter.select(p, memory);
        Intent intent = decision.intent();

        // 1. Aim toward the intent's facing.
        applyFacing(p, intent.facing());

        // 2. Motor: make the desired world heading collision-smart, then quantize.
        MoveHeading heading = resolveHeading(p, intent, world, decision.goal().mayLeaveLedges());
        JumpHint jump = proactiveJump.evaluate(p, heading, world, memory);
        if (intent.jump() == JumpHint.JUMP) {
            jump = JumpHint.JUMP;
        }
        // Consume the collision-aware ease-off: duty-cycle the softened forward for
        // heading.speedScale() < 1 and sneak near a ledge, keyed off a monotonic phase.
        MoveInput move = motor.toInput(heading, aim.yaw(), intent.wantSprint(), jump, false,
                memory.motorTick++);

        // 3. Actions: the goal/routine's item action, then CPS-clocked clicks.
        List<ActionIntent> actions = new ArrayList<>(2);
        if (!(intent.action() instanceof ActionIntent.None)) {
            actions.add(intent.action());
        }
        boolean suppressed = p.self().useItem() == Perception.UseItemState.USING
                || intent.action() instanceof ActionIntent.StartUse
                || decision.goal().suppressesAttack();
        clicks.consider(p, aim.yaw(), s.reach(), s.aimToleranceDegrees(), suppressed,
                s.combat().missChance(), clicker, nowMs, memory, actions);

        // Blockhit layer: only while actually fighting (a routine that suppresses
        // attacks is holding a rod/pot/food, not a sword to block with).
        if (!decision.goal().suppressesAttack() && p.hasTarget()) {
            boolean attackFiring = actions.stream().anyMatch(a -> a instanceof ActionIntent.Attack);
            boolean inMelee = p.target().distance() <= s.reach() + 0.5;
            blockhit.apply(p, s.combat().blockHit(), inMelee, attackFiring, memory, actions);
        }

        return new BrainOutput(move, aim.yaw(), aim.pitch(), actions, move.sprint());
    }

    /** Drive the aim spring one step toward where the intent wants to look. */
    private void applyFacing(Perception p, FacingIntent facing) {
        Perception.SelfState self = p.self();
        float desiredYaw;
        float desiredPitch;
        if (facing instanceof FacingIntent.AimAt aimAt) {
            double dx = aimAt.x() - self.x();
            double dz = aimAt.z() - self.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            double eyeDy = aimAt.y() - (self.y() + 1.62);
            desiredPitch = (float) -Math.toDegrees(Math.atan2(eyeDy, Math.max(dist, 1.0E-4)));
        } else {
            // FaceMove: look along travel; hold pitch level. Keep the current yaw if idle.
            Vec3d vel = self.velocity();
            if (vel.horizontalDistanceSqr() > 1.0E-6) {
                desiredYaw = (float) Math.toDegrees(Math.atan2(-vel.x(), vel.z()));
            } else {
                desiredYaw = aim.yaw();
            }
            desiredPitch = 0.0f;
        }
        aim.step(desiredYaw, desiredPitch);
    }

    /**
     * Turn the intent's desired world direction into a collision-aware heading:
     * context steering slides along walls; when the boxer is genuinely trapped,
     * anti-stuck detours and (if that keeps failing) a bounded path planner routes
     * around the obstacle toward the target.
     */
    private MoveHeading resolveHeading(Perception p, Intent intent, CollisionView world,
            boolean mayLeaveLedges) {
        Vec3d desired = intent.moveDirWorld();
        if (desired.horizontalDistanceSqr() < 1.0E-8) {
            memory.clearPath();
            return MoveHeading.STILL;
        }

        // Commit to an in-progress route until it is consumed — don't drop it the
        // moment short-term progress resumes, or the boxer thrashes between routing
        // around the obstacle and steering straight back into it.
        if (memory.path != null) {
            MoveHeading routed = followRoute(p, world, mayLeaveLedges);
            if (routed != null) {
                return routed;
            }
            memory.clearPath();
        }

        // Elevation-aware pathing (proactive, not a stuck-rescue): when the target
        // sits on a different level — a platform reachable only by stairs/step-ups
        // that may run AWAY from the direct line — plan a 3D route now, so the boxer
        // seeks the access route instead of pressing uselessly straight under (or
        // over) the target. Reactive steering can't discover an off-line staircase.
        if (needsElevationRoute(p, desired) && planRoute(p, world)) {
            MoveHeading routed = followRoute(p, world, mayLeaveLedges);
            if (routed != null) {
                return routed;
            }
        }

        MoveHeading heading = steering.steer(p, desired, world, mayLeaveLedges);

        // Advance the stall counter every tick, but only ACT on it when the boxer
        // is genuinely trying to close on the target FROM A DISTANCE (an approach
        // through terrain). A deliberate orbit makes little net approach, and a
        // boxer pressing into a target in the melee pocket is "stuck" only because
        // entity pushing holds it there — neither must be "rescued" into a detour.
        boolean stuck = antiStuck.isStuck(p, memory);
        boolean closingFromAfar = p.target() != null && p.target().distance() > 2.5;
        if (!stuck || !isApproaching(p, desired) || !closingFromAfar) {
            return heading;
        }
        if (antiStuck.shouldReroute(memory) && planRoute(p, world)) {
            MoveHeading routed = followRoute(p, world, mayLeaveLedges);
            if (routed != null) {
                return routed;
            }
        }
        return antiStuck.detour(p, heading, world, memory);
    }

    /** True when the desired heading is mostly toward the target (closing distance). */
    private static boolean isApproaching(Perception p, Vec3d desired) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        Vec3d toTarget = new Vec3d(t.x() - p.self().x(), 0.0, t.z() - p.self().z())
                .horizontalNormalized();
        return desired.horizontalNormalized().dot(toTarget) > 0.4;
    }

    /** Vertical gap (blocks) beyond which the target counts as a different LEVEL — more
     *  than a running jump can bridge directly, so it needs a planned access route. */
    private static final double ELEVATION_GAP = 1.5;

    /**
     * True when the target sits on a different level than the boxer (a raised or sunken
     * platform more than a step/jump away vertically) and the boxer is trying to close
     * on it. Reactive steering reasons only in the horizontal plane, so it cannot
     * discover an off-line staircase up to the target; the 3D planner can, and running
     * it proactively here (not as a stuck-rescue) is what makes the boxer go find the
     * stairs instead of stalling directly under the platform.
     */
    private static boolean needsElevationRoute(Perception p, Vec3d desired) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        boolean elevationGap = Math.abs(t.y() - p.self().y()) > ELEVATION_GAP;
        return elevationGap && t.distance() > 2.0 && isApproaching(p, desired);
    }

    /** Plan a fresh bounded route to the target; returns true and stores it if found. */
    private boolean planRoute(Perception p, CollisionView world) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        Vec3d self = p.self().position();
        // Stall recovery: the boxer is stuck precisely because a reactive step-up
        // didn't get it through, so route AROUND the obstacle (walk-only) first.
        // Only fall back to a jump-allowed route if there is genuinely no way around.
        Optional<List<Vec3d>> route = planner.route(self, t.position(), world, PLAN_BUDGET, false);
        if (route.isEmpty() || route.get().isEmpty()) {
            route = planner.route(self, t.position(), world, PLAN_BUDGET, true);
        }
        if (route.isEmpty() || route.get().isEmpty()) {
            return false;
        }
        memory.path = route.get();
        memory.pathCursor = 0;
        memory.lastGoalCell = cell(t.x(), t.z());
        return true;
    }

    /**
     * Follow the cached route: advance past a reached waypoint and steer toward the
     * next. Returns {@code null} — signalling the caller to drop the route — when it
     * is exhausted or the target has wandered far from where the route was planned.
     */
    private MoveHeading followRoute(Perception p, CollisionView world, boolean mayLeaveLedges) {
        if (memory.path == null || memory.pathCursor >= memory.path.size()) {
            return null;
        }
        Perception.TargetState t = p.target();
        if (t == null || cell(t.x(), t.z()) != memory.lastGoalCell) {
            return null; // the target moved to a different cell — replan from scratch
        }
        Vec3d self = p.self().position();
        Vec3d waypoint = memory.path.get(memory.pathCursor);
        if (self.subtract(waypoint).horizontalDistanceSqr() < WAYPOINT_REACHED_SQ) {
            memory.pathCursor++;
            if (memory.pathCursor >= memory.path.size()) {
                return null;
            }
            waypoint = memory.path.get(memory.pathCursor);
        }
        Vec3d toWaypoint = waypoint.subtract(self).horizontalNormalized();
        if (toWaypoint.horizontalDistanceSqr() < 1.0E-8) {
            return null;
        }
        return steering.steer(p, toWaypoint, world, mayLeaveLedges);
    }

    private static long cell(double x, double z) {
        return (((long) Math.floor(x)) << 32) ^ (((long) Math.floor(z)) & 0xFFFFFFFFL);
    }
}

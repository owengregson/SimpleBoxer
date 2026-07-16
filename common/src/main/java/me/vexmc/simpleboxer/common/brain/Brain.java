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
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
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

    /** Expansion budget of the flat stuck-rescue planner passes. Package-visible, like
     *  the elevation constants below, so the planner test battery can exercise the
     *  PRODUCTION numbers instead of inventing its own. */
    static final int PLAN_BUDGET = 400;
    /**
     * Expansion budget of a proactive elevation plan — the planner suite's own
     * elevated-stairs reference budget. Planning is rare (self-throttled below) and
     * pure grid A* is microseconds, so the budget errs toward completeness: at the
     * {@link BaritoneStylePlanner#MAX_EXTENT_CAP} 32-cell box (65×65 = 4225 columns,
     * typically one standable Y level each) 8000 expansions cover the practical
     * search space, where the old 400 could not even fill the default 10-cell box.
     */
    static final int ELEVATION_PLAN_BUDGET = 8000;
    /** Stair-hunting margin: the search box reaches this many cells PAST the goal. */
    static final int ELEVATION_EXTENT_MARGIN = 8;
    /** Decision ticks between elevation-plan attempts (a failed 8000-expansion
     *  search must not rerun every tick; also the replan-while-following cadence). */
    static final int ELEVATION_RETRY_TICKS = 20;
    private static final double WAYPOINT_REACHED_SQ = 1.0;
    /** Decision ticks a follower may spend on ONE waypoint before the route is
     *  deemed geometrically invalid (blocked by changed terrain, or the boxer was
     *  knocked hopelessly off the line) — collapsed straight runs can legitimately
     *  put a waypoint 10+ blocks out, so the clock, not distance, is the judge. */
    private static final int WAYPOINT_STALL_TICKS = 60;
    /** How close the boxer's feet must get to the latched target level to release
     *  the climb latch (within a step/jump of "arrived"). */
    private static final double CLIMB_ARRIVED = 0.75;

    private volatile BoxerSettings settings;

    private final AimSpring aim;
    private final ClickScheduler clicker;
    private final BrainMemory memory;
    private final Arbiter arbiter;

    private final AdaptiveStrafe adaptiveStrafe = new AdaptiveStrafe();
    private final ContextSteering steering = new ContextSteering();
    private final ProactiveJump proactiveJump = new ProactiveJump();
    private final CritSpam critSpam = new CritSpam();
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
        JumpHint jump = proactiveJump.evaluate(p, heading, intent.wantSprint(), world, memory);
        if (intent.jump() == JumpHint.JUMP) {
            jump = JumpHint.JUMP;
        }
        // Ceiling crit-spam ORs its hop in exactly like an intent-level jump and
        // asks for the sprint drop crits need server-side; it self-gates on the
        // engage goal owning the tick, the w-tap release window, the melee band,
        // and a crit-eligible roof overhead.
        CritSpam.Decision crit = critSpam.evaluate(p, decision.goal().id(), s, world, memory);
        if (crit.jump() == JumpHint.JUMP) {
            jump = JumpHint.JUMP;
        }
        boolean wantSprint = intent.wantSprint() && !crit.dropSprint();
        // Consume the collision-aware ease-off: duty-cycle the softened forward for
        // heading.speedScale() < 1 and sneak near a ledge, keyed off a monotonic phase.
        MoveInput move = motor.toInput(heading, aim.yaw(), wantSprint, jump, false,
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
        // attacks is holding a rod/pot/food, not a sword to block with). While the
        // crit-spam hop owns the rhythm no NEW tap is raised — a real crit-spammer
        // does not sword-block mid-hop, and a tap's next-tick USING state would eat
        // a descending-window click. The hold rides the attack-firing parameter,
        // never the enabled flag: disabling zeroes the phase state and would
        // swallow the release paired with a tap raised the tick before activation.
        if (!decision.goal().suppressesAttack() && p.hasTarget()) {
            boolean attackFiring = actions.stream().anyMatch(a -> a instanceof ActionIntent.Attack);
            boolean inMelee = p.target().distance() <= s.reach() + 0.5;
            boolean critHold = CritSpam.activeThisTick(memory, p.combat().serverTick());
            blockhit.apply(p, s.combat().blockHit(), inMelee, attackFiring || critHold, memory, actions);
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
     * a committed route is followed first (and refreshed in place when the target
     * drifts); a target on another level triggers proactive 3D planning; context
     * steering slides along walls; and when the boxer is genuinely trapped,
     * anti-stuck detours and (if that keeps failing) a bounded path planner routes
     * around the obstacle toward the target.
     */
    private MoveHeading resolveHeading(Perception p, Intent intent, CollisionView world,
            boolean mayLeaveLedges) {
        Vec3d desired = intent.moveDirWorld();
        // The scheduled-takeoff cue is per-tick state: it must not linger once the
        // route stops asking for a climb (followRoute re-arms it below).
        memory.routeStepFace = Double.NaN;
        memory.routeStepRise = 0.0;
        if (desired.horizontalDistanceSqr() < 1.0E-8) {
            memory.clearPath();
            memory.climbLatch = false;
            return MoveHeading.STILL;
        }

        // Commit to an in-progress route until it is consumed — don't drop it the
        // moment short-term progress resumes, or the boxer thrashes between routing
        // around the obstacle and steering straight back into it. A latched climb
        // additionally survives target cell changes: refreshRoute swaps in a
        // replacement plan when one exists, otherwise the committed stairs keep
        // being climbed (a 1-block strafe on the platform rarely changes access).
        if (memory.path != null) {
            refreshRoute(p, world);
            MoveHeading routed = followRoute(p, world, mayLeaveLedges);
            if (routed != null) {
                return routed;
            }
            memory.clearPath();
            memory.climbLatch = false;
        }

        // Elevation-aware pathing (proactive, not a stuck-rescue): when the target
        // sits on a different level — a platform reachable only by stairs/step-ups
        // that may run AWAY from the direct line — plan a 3D route now, so the boxer
        // seeks the access route instead of pressing uselessly straight under (or
        // over) the target. Reactive steering can't discover an off-line staircase.
        if (needsElevationRoute(p, desired) && planElevationRoute(p, world)) {
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
        // A target more than a level away counts as afar REGARDLESS of horizontal
        // distance: the melee-pocket exemption only makes sense when the pocket
        // can actually reach the target (dy omitted from distance parked boxers
        // under platforms with every planning gate shut off).
        boolean stuck = antiStuck.isStuck(p, memory);
        boolean closingFromAfar = p.target() != null
                && (p.target().distance() > 2.5
                        || Math.abs(p.target().y() - p.self().y()) > ELEVATION_GAP);
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
     * True when the target sits on a different level than the boxer (a raised or
     * sunken platform more than a step/jump away vertically) and is genuinely out
     * of reach. Reactive steering reasons only in the horizontal plane, so it
     * cannot discover an off-line staircase up to the target; the 3D planner can.
     * Gated on TRUE 3D range (a target 3 blocks straight up reads horizontal
     * distance ~0 — the old horizontal-only gate shut planning off exactly at the
     * dead end under the platform) and on not actively retreating (orbits are
     * tangential, dot ≈ 0, and must still plan — the old isApproaching &gt; 0.4
     * gate starved strafe styles; a fleeing heal/food routine, dot ≈ −1, must not).
     */
    private static boolean needsElevationRoute(Perception p, Vec3d desired) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        double dy = t.y() - p.self().y();
        if (Math.abs(dy) <= ELEVATION_GAP) {
            return false;
        }
        if (t.distance() * t.distance() + dy * dy <= 4.0) {
            return false; // within true 3D reach of the pocket — no route needed
        }
        Vec3d toTarget = new Vec3d(t.x() - p.self().x(), 0.0, t.z() - p.self().z())
                .horizontalNormalized();
        return desired.horizontalNormalized().dot(toTarget) > -0.25;
    }

    /**
     * Plan a fresh bounded route to the target; returns true and stores it if found.
     * An elevated target routes through {@link #planElevationRoute} even from the
     * stuck-rescue entry: the walk-only-first policy below is actively wrong for a
     * level change (walk-only can never ASCEND, and its anytime partial dead-ends
     * under the target, short-circuiting the jump pass that WOULD find the stairs).
     */
    private boolean planRoute(Perception p, CollisionView world) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        if (Math.abs(t.y() - p.self().y()) > ELEVATION_GAP) {
            return planElevationRoute(p, world);
        }
        Vec3d self = p.self().position();
        // Stall recovery on the flat: the boxer is stuck precisely because a reactive
        // step-up didn't get it through, so route AROUND the obstacle (walk-only)
        // first. Only fall back to a jump-allowed route if there is no way around.
        Optional<BaritoneStylePlanner.Route> route = planner.plan(self, t.position(), world,
                PLAN_BUDGET, false, BaritoneStylePlanner.MAX_EXTENT);
        if (route.isEmpty() || route.get().waypoints().isEmpty()) {
            route = planner.plan(self, t.position(), world,
                    PLAN_BUDGET, true, BaritoneStylePlanner.MAX_EXTENT);
        }
        if (route.isEmpty() || route.get().waypoints().isEmpty()) {
            return false;
        }
        memory.path = route.get().waypoints();
        memory.pathCursor = 0;
        memory.waypointTicks = 0;
        memory.lastGoalCell = cell(t.x(), t.z());
        return true;
    }

    /** Search-box half-width for an elevation plan: the goal plus a stair-hunting
     *  margin, never below the planner default, capped at the planner's Folia bound. */
    static int elevationExtent(int chebyshevCells) {
        return Math.min(BaritoneStylePlanner.MAX_EXTENT_CAP,
                Math.max(BaritoneStylePlanner.MAX_EXTENT, chebyshevCells + ELEVATION_EXTENT_MARGIN));
    }

    /**
     * Proactive elevation planning: ONE jump-allowed pass over an adaptive box that
     * always covers the target plus {@link #ELEVATION_EXTENT_MARGIN} cells of
     * stair-hunting margin, at the tested {@link #ELEVATION_PLAN_BUDGET}. A partial
     * result is adopted only when its endpoint makes real LEVEL progress toward the
     * target (at least half a block closer to its Y) — the failure mode this
     * replaces was a walk-level breadcrumb dead-ending directly under the platform.
     * Self-throttled to one attempt per {@link #ELEVATION_RETRY_TICKS} decision
     * ticks; on success the climb latch arms so the follower holds the route.
     */
    private boolean planElevationRoute(Perception p, CollisionView world) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        if (memory.motorTick < memory.lastPlanTick + ELEVATION_RETRY_TICKS) {
            return false;
        }
        memory.lastPlanTick = memory.motorTick;
        Vec3d self = p.self().position();
        int cheb = Math.max(
                Math.abs((int) Math.floor(t.x()) - (int) Math.floor(self.x())),
                Math.abs((int) Math.floor(t.z()) - (int) Math.floor(self.z())));
        Optional<BaritoneStylePlanner.Route> found = planner.plan(self, t.position(), world,
                ELEVATION_PLAN_BUDGET, true, elevationExtent(cheb));
        if (found.isEmpty() || found.get().waypoints().isEmpty()) {
            return false;
        }
        BaritoneStylePlanner.Route route = found.get();
        if (!route.complete()
                && Math.abs(t.y() - route.endFloorY()) > Math.abs(t.y() - self.y()) - 0.5) {
            return false; // an under-target dead-end breadcrumb — steer reactively instead
        }
        memory.path = route.waypoints();
        memory.pathCursor = 0;
        memory.waypointTicks = 0;
        memory.lastGoalCell = cell(t.x(), t.z());
        memory.climbLatch = true;
        memory.climbGoalY = t.y();
        return true;
    }

    /**
     * Replan-while-following: when the latched target has wandered to a different
     * cell, try to mint a replacement route WITHOUT dropping the committed one —
     * the old route keeps driving the boxer (mid-climb especially) until a better
     * plan exists. Only the elevation planner runs here (it self-throttles); flat
     * pursuit keeps the classic drop-and-resteer in {@link #followRoute}.
     */
    private void refreshRoute(Perception p, CollisionView world) {
        if (!memory.climbLatch) {
            return;
        }
        Perception.TargetState t = p.target();
        if (t == null || cell(t.x(), t.z()) == memory.lastGoalCell) {
            return;
        }
        // On success this swaps path/cursor/goal-cell atomically; on failure (or
        // while throttled) it leaves the committed route untouched.
        planElevationRoute(p, world);
    }

    /**
     * Follow the cached route: advance past a reached waypoint and steer toward the
     * next. Returns {@code null} — signalling the caller to drop the route — when it
     * is exhausted, when a flat-pursuit target has wandered to a new cell, or when
     * the boxer has been knocked so far off the line the route is stale geometry.
     * A latched climb ignores target cell changes (refreshRoute handles those) and
     * releases the latch once the boxer's feet reach the target level. An ASCEND
     * step to the next waypoint arms the scheduled-takeoff cue for ProactiveJump.
     */
    private MoveHeading followRoute(Perception p, CollisionView world, boolean mayLeaveLedges) {
        if (memory.path == null || memory.pathCursor >= memory.path.size()) {
            return null;
        }
        if (memory.climbLatch
                && Math.abs(p.self().y() - memory.climbGoalY) <= CLIMB_ARRIVED) {
            memory.climbLatch = false; // arrived at the target's level — normal rules resume
        }
        Perception.TargetState t = p.target();
        if (!memory.climbLatch
                && (t == null || cell(t.x(), t.z()) != memory.lastGoalCell)) {
            return null; // flat pursuit: the target moved to a different cell — replan from scratch
        }
        Vec3d self = p.self().position();
        Vec3d waypoint = memory.path.get(memory.pathCursor);
        if (self.subtract(waypoint).horizontalDistanceSqr() < WAYPOINT_REACHED_SQ) {
            memory.pathCursor++;
            memory.waypointTicks = 0;
            if (memory.pathCursor >= memory.path.size()) {
                return null;
            }
            waypoint = memory.path.get(memory.pathCursor);
        }
        if (++memory.waypointTicks > WAYPOINT_STALL_TICKS) {
            return null; // a waypoint we cannot reach — the route is stale geometry now
        }
        Vec3d toWaypoint = waypoint.subtract(self);
        Vec3d heading = toWaypoint.horizontalNormalized();
        if (heading.horizontalDistanceSqr() < 1.0E-8) {
            return null;
        }
        double rise = waypoint.y() - p.self().y();
        if (rise > ClientPhysics.STEP_HEIGHT && rise <= NavGeometry.MAX_JUMP_RISE + 1.0E-6) {
            // Scheduled ASCEND takeoff: hand ProactiveJump the step-face distance —
            // the waypoint cell's near face sits half a cell before its centre, and
            // the box's leading edge a server half-width before the body centre.
            memory.routeStepFace = Math.max(0.0,
                    Math.sqrt(toWaypoint.horizontalDistanceSqr())
                            - 0.5 - ClientPhysics.PLAYER_WIDTH / 2.0);
            memory.routeStepRise = rise;
        }
        return steering.steer(p, heading, world, mayLeaveLedges);
    }

    private static long cell(double x, double z) {
        return (((long) Math.floor(x)) << 32) ^ (((long) Math.floor(z)) & 0xFFFFFFFFL);
    }
}

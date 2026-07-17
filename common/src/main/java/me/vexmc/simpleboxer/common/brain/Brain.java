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
import org.jetbrains.annotations.Nullable;

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
    /** Post-hit window (decision ticks ≈ 2 s — two full re-close cycles) during
     *  which no search may start or step while the target sits inside
     *  {@link #COMBAT_HOLD_DISTANCE}: the 3-6-block post-knockback band is
     *  combat, not navigation. Stamped by {@link #onHitLanded}. */
    static final int COMBAT_HOLD_TICKS = 40;
    /** Max sprint-knockback separation (+1 margin) — the far edge of the
     *  re-close band the combat hold protects. */
    static final double COMBAT_HOLD_DISTANCE = 6.0;
    /** Consecutive decision ticks the ground-snapped gap must exceed
     *  {@link #ELEVATION_GAP} before the elevation gate opens: the longest
     *  measured ballistic transient (a jump onto +1 terrain) holds ~8 ticks,
     *  so 10 buys transient immunity for half a second of latency on genuine
     *  elevation. */
    static final int ELEVATION_GAP_PERSIST_TICKS = 10;
    /** Node expansions a sliced search advances per decision tick. With ring-1
     *  clearance a first-visit expansion costs ≲ ~40 collidingBoxes calls (8
     *  neighbour ground/headroom probes plus up to 8 memoized-once clearance
     *  cells at ~2 calls each — revisits far cheaper), so one slice stays
     *  ≲ ~2k calls: well under a millisecond live, against the 20 ms-to-seconds
     *  single-tick plans it replaces. */
    static final int SEARCH_SLICE_EXPANSIONS = 50;
    /** Target-cell drift (Chebyshev) beyond which an in-flight search is stale
     *  and abandoned. Half the stair-hunting margin: within it the clamped goal
     *  still sits in the search box, and adoption re-aims the boxer through the
     *  nearest-point-ahead join. */
    static final int SEARCH_DRIFT_CELLS = 4;
    /** Decision ticks between stuck-rescue search attempts — the lateral detour
     *  owns the ticks in between. */
    static final int RESCUE_RETRY_TICKS = 10;
    /** Decision ticks a corridor verdict stays cached before the line re-probes
     *  (a target cell change re-probes immediately). */
    static final int CORRIDOR_RECHECK_TICKS = 5;
    /** Hard cap on the dynamic drop budget, in blocks: bounds every ledge probe
     *  and the planner's downward band regardless of how much fall damage the
     *  boxer could survive (Feather Falling IV budgets compute to 22+). */
    static final double SAFE_DROP_CAP = 16.0;
    /** How far ahead of the boxer's path projection the route follower steers —
     *  the join point is always AHEAD along the polyline, never a passed cell. */
    private static final double ROUTE_LOOKAHEAD = 1.75;
    /** searchKind tags: who opened the in-flight search (decides adoption policy). */
    static final int SEARCH_ELEVATION = 1;
    static final int SEARCH_RESCUE_WALK = 2;
    static final int SEARCH_RESCUE_JUMP = 3;

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
    private final HandControl hand = new HandControl();

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

    /**
     * Respawn: the ServerPlayer entity was replaced — reset every mid-episode
     * transient (routine FSMs, committed routes, combo state) so the new life
     * starts from the arbiter's baseline instead of resuming a dead boxer's
     * half-finished routine with its tool slot still selected.
     */
    public void onRespawn() {
        memory.onRespawn();
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
        // Combat-exclusion stamp: a landed hit opens the re-close window during
        // which navigation may not steal the tick (see COMBAT_HOLD_TICKS).
        memory.lastHitTick = memory.motorTick;
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
        // The goal's ledge policy becomes a DROP BUDGET in blocks: pursuit may
        // walk off any edge whose predicted fall damage fits half the current
        // max health (enchants, Jump Boost, multiplier and Slow Falling all
        // priced by FallDamage's exact inverse), capped for probe/band sanity;
        // ledge-averse goals keep the classic conservative depth. Every layer
        // below — steering, key guard, anti-stuck, corridor, planner — reads
        // this SAME number.
        Perception.SelfState self = p.self();
        double safeDrop = Math.min(SAFE_DROP_CAP, FallDamage.maxSafeFallBlocks(
                0.5 * self.maxHealth(), self.fallEpf(), self.safeFallDistance(),
                self.fallDamageMultiplier(), self.slowFalling()));
        double dropBudget = decision.goal().mayLeaveLedges()
                ? safeDrop : ContextSteering.LEDGE_MAX_DROP;
        MoveHeading heading = resolveHeading(p, intent, world, dropBudget);
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
        // The deadband can promote a small steering-approved ledge-ward component
        // into a full diagonal key press — re-check the REALIZED key direction
        // against the same drop budget steering used, releasing a key over the
        // lip. With the dynamic budget this now guards pursuit too: a
        // within-budget drop passes untouched, a beyond-budget lip releases.
        if (!heading.isStill()) {
            move = LedgeKeyGuard.mask(move, heading, aim.yaw(), world,
                    NavGeometry.playerBox(self.x(), self.y(), self.z()), dropBudget);
        }

        // 3. Actions: the hand machine owns every slot/use/release emission —
        //    the goal's action is a REQUEST it validates, sequences, and pairs
        //    (an interrupted hold is released before the interrupting action, a
        //    use never fires on a slot the ledger is not on), and its gate
        //    decides click suppression: clicks are free exactly when the ledger
        //    holds the weapon and nothing but a sword block is raised —
        //    deterministic at any ping.
        List<ActionIntent> actions = new ArrayList<>(4);
        boolean routineOwnsHand = decision.goal().suppressesAttack();
        HandControl.Gate gate = hand.route(p, memory.hand, decision.goal().id(),
                intent.action(), routineOwnsHand, s.items().weaponSlot(), actions);
        clicks.consider(p, aim.yaw(), s.reach(), s.aimToleranceDegrees(), !gate.clicksFree(),
                s.combat().missChance(), clicker, nowMs, memory, actions);

        // Blockhit layer: the machine grants the window (routine idle, ledger on
        // the weapon, nothing held or owed — a tap can never displace a use or
        // slip between a swap and its landing); the combat gates stay here.
        // While the crit-spam hop owns the rhythm no NEW tap is raised — a real
        // crit-spammer does not sword-block mid-hop, and a tap's raised tick
        // must not shadow a descending-window click. The machine pays the tap's
        // release next tick no matter who owns that tick.
        boolean tapDesired = false;
        if (gate.tapWindow() && p.hasTarget()) {
            boolean attackFiring = actions.stream().anyMatch(a -> a instanceof ActionIntent.Attack);
            boolean inMelee = p.target().distance() <= s.reach() + 0.5;
            boolean critHold = CritSpam.activeThisTick(memory, p.combat().serverTick());
            tapDesired = blockhit.desire(p, s.combat().blockHit(), inMelee,
                    attackFiring || critHold, memory);
        }
        hand.finish(memory.hand, tapDesired, actions);

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
     * Turn the intent's desired world direction into a collision-aware heading.
     * Priority order, one tick: a verified straight corridor to the target is
     * taken immediately (no route, no search); a committed route is joined at
     * the nearest point ahead and followed; otherwise the sliced planner is
     * advanced (or opened, when the hysteresis-gated elevation gate holds) while
     * context steering keeps the boxer moving; and a genuinely trapped boxer
     * falls to anti-stuck detours and the sliced stuck-rescue search. The
     * post-hit combat hold freezes every search interaction — the re-close band
     * belongs to combat.
     */
    private MoveHeading resolveHeading(Perception p, Intent intent, CollisionView world,
            double dropBudget) {
        Vec3d desired = intent.moveDirWorld();
        // The scheduled-takeoff cue is per-tick state: it must not linger once the
        // route stops asking for a climb (followRoute re-arms it below).
        memory.routeStepFace = Double.NaN;
        memory.routeStepRise = 0.0;
        // The gap hysteresis advances EVERY decision tick — release windows
        // included — so "consecutive" means wall-clock decision ticks.
        updateElevationGap(p, world);
        if (desired.horizontalDistanceSqr() < 1.0E-8) {
            // A deliberate stand-still — most often the w-tap release window.
            // HOLD the committed route and latch: a release window is part of
            // the combat rhythm, never grounds for a replan (the follower's
            // stall clock pauses with them; followRoute is not consulted this
            // tick).
            return MoveHeading.STILL;
        }

        // Straight-corridor shortcut: a verified walkable line to the target
        // (budget drops included) beats routes and searches alike — take it now.
        if (corridorClear(p, world, dropBudget)) {
            memory.clearPath();
            memory.climbLatch = false;
            memory.search = null;
            return steerTail(p, desired, world, dropBudget, true);
        }

        // A rescue search stays meaningful only while anti-stuck still wants it;
        // any search dies when its goal drifts beyond the box margin.
        if (memory.search != null && memory.searchKind != SEARCH_ELEVATION
                && !antiStuck.shouldReroute(memory)) {
            memory.search = null;
        }
        invalidateStaleSearch(p);
        // Combat exclusion: inside the post-hit re-close window no search starts
        // or steps — the 3-6-block post-knockback band is combat, not navigation.
        boolean hold = combatHold(p);
        if (memory.search != null && !hold) {
            stepSearch(p, world, dropBudget);
        }

        if (memory.path != null) {
            // Replan-while-following: a latched climb whose target wandered to a
            // new cell mints a REPLACEMENT search without dropping the committed
            // route — the old stairs keep being climbed until a better plan lands.
            if (!hold && memory.search == null && memory.climbLatch
                    && !searchSteppedThisTick()) {
                Perception.TargetState t = p.target();
                if (t != null && cell(t.x(), t.z()) != memory.lastGoalCell
                        && memory.motorTick >= memory.lastPlanTick + ELEVATION_RETRY_TICKS) {
                    memory.lastPlanTick = memory.motorTick;
                    beginSearch(p, world, SEARCH_ELEVATION, dropBudget);
                    stepSearch(p, world, dropBudget);
                }
            }
            MoveHeading routed = followRoute(p, world, dropBudget);
            if (routed != null) {
                return routed;
            }
            memory.clearPath();
            memory.climbLatch = false;
        } else if (!hold && memory.search == null && !searchSteppedThisTick()
                && needsElevationRoute(p, desired)
                && memory.motorTick >= memory.lastPlanTick + ELEVATION_RETRY_TICKS) {
            memory.lastPlanTick = memory.motorTick;
            beginSearch(p, world, SEARCH_ELEVATION, dropBudget);
            stepSearch(p, world, dropBudget); // first slice now; a trivial search adopts at once
            if (memory.path != null) {
                MoveHeading routed = followRoute(p, world, dropBudget);
                if (routed != null) {
                    return routed;
                }
                memory.clearPath();
                memory.climbLatch = false;
            }
        }

        return steerTail(p, desired, world, dropBudget, false);
    }

    /**
     * The keep-moving tail every no-route tick funnels into: context steering,
     * then anti-stuck escalation (whose rescue is a sliced search like every
     * other — never a synchronous plan).
     */
    private MoveHeading steerTail(Perception p, Vec3d desired, CollisionView world,
            double dropBudget, boolean corridor) {
        MoveHeading heading = steering.steer(p, desired, world, dropBudget);

        // Advance the stall counter every tick, but only ACT on it when the boxer
        // is genuinely trying to close on the target FROM A DISTANCE (an approach
        // through terrain). A deliberate orbit makes little net approach, and a
        // boxer pressing into a target in the melee pocket is "stuck" only because
        // entity pushing holds it there — neither must be "rescued" into a detour.
        // A target more than a level away counts as afar REGARDLESS of horizontal
        // distance, on the SAME ground-snapped, hysteresis-gated gap the elevation
        // gate reads — a crammed crit-hopping boxer cannot escalate off its own
        // jump arc any more.
        boolean stuck = antiStuck.isStuck(p, memory);
        boolean closingFromAfar = p.target() != null
                && (p.target().distance() > 2.5
                        || memory.elevationGapTicks >= ELEVATION_GAP_PERSIST_TICKS);
        // Two further exemptions, same spirit as the pocket/orbit gates:
        //  - accelerating is not stuck: the 3-tick flag trips before vanilla
        //    from-rest acceleration can bank the net-progress bar (positions run
        //    0, 0.025, 0.074, … from a standing start), so while per-tick
        //    displacement is still strictly climbing the boxer is STARTING —
        //    detouring here strangles the launch into a self-sustaining wiggle
        //    (the wiggle itself banks no net progress, so the flag never decays);
        //  - corridor-clear exempts escalation outright, exactly like the melee
        //    pocket: with the straight line VERIFIED walkable, terrain cannot be
        //    the pin — only entity pressure — and the answer is to keep pressing
        //    the verified line (the corridor branch above kills any rescue search
        //    next tick anyway, so the old reroute escape does not exist here).
        if (!stuck || memory.gainingSpeed() || !isApproaching(p, desired)
                || !closingFromAfar || corridor) {
            return heading;
        }
        // Stuck rescue. A target a level away rescues through the ELEVATION
        // search instead — walk-only can never ASCEND, and its anytime partial
        // dead-ends under the target (the deeper fall band rides along too).
        if (!combatHold(p) && antiStuck.shouldReroute(memory)
                && memory.search == null && !searchSteppedThisTick()
                && memory.motorTick >= memory.lastRescuePlanTick + RESCUE_RETRY_TICKS) {
            memory.lastRescuePlanTick = memory.motorTick;
            int kind = memory.elevationGapTicks >= ELEVATION_GAP_PERSIST_TICKS
                    ? SEARCH_ELEVATION : SEARCH_RESCUE_WALK;
            if (kind == SEARCH_ELEVATION) {
                memory.lastPlanTick = memory.motorTick; // one elevation throttle for both entries
            }
            beginSearch(p, world, kind, dropBudget);
            stepSearch(p, world, dropBudget);
            if (memory.path != null) {
                MoveHeading routed = followRoute(p, world, dropBudget);
                if (routed != null) {
                    return routed;
                }
                memory.clearPath();
                memory.climbLatch = false;
            }
        }
        return antiStuck.detour(p, heading, world, memory, dropBudget);
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
     * Refresh the ground-snapped gap between the target's standing level and the
     * boxer's — BOTH columns snapped exactly the way the planner snaps its start
     * and goal, so a jumping/knocked-up target (or the boxer's own hop arc) no
     * longer reads as "on another level" — and advance the persistence counter
     * the elevation gate and the anti-stuck escape hatch both consume.
     */
    private void updateElevationGap(Perception p, CollisionView world) {
        Perception.TargetState t = p.target();
        if (t == null) {
            memory.targetGroundGap = 0.0;
            memory.elevationGapTicks = 0;
            return;
        }
        double targetGround = NavGeometry.groundHeight(world,
                Math.floor(t.x()) + 0.5, Math.floor(t.z()) + 0.5, t.y());
        double selfGround = NavGeometry.groundHeight(world,
                Math.floor(p.self().x()) + 0.5, Math.floor(p.self().z()) + 0.5, p.self().y());
        double gap = (Double.isNaN(targetGround) ? t.y() : targetGround)
                - (Double.isNaN(selfGround) ? p.self().y() : selfGround);
        memory.targetGroundGap = gap;
        memory.elevationGapTicks =
                Math.abs(gap) > ELEVATION_GAP ? memory.elevationGapTicks + 1 : 0;
    }

    /** The post-hit re-close window: navigation may not steal these ticks. */
    private boolean combatHold(Perception p) {
        Perception.TargetState t = p.target();
        return t != null && t.distance() < COMBAT_HOLD_DISTANCE
                && memory.motorTick - memory.lastHitTick < COMBAT_HOLD_TICKS;
    }

    /** Whether a search slice already ran this decision tick (the per-tick cap). */
    private boolean searchSteppedThisTick() {
        return memory.lastSearchStepTick == memory.motorTick;
    }

    /**
     * The cached straight-corridor verdict, re-probed every
     * {@link #CORRIDOR_RECHECK_TICKS} ticks or on a target cell change. While
     * true, planning is superseded outright — requirement (1)'s "take the clear
     * straight path immediately".
     */
    private boolean corridorClear(Perception p, CollisionView world, double dropBudget) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return false;
        }
        long goalCell = cell(t.x(), t.z());
        if (goalCell == memory.corridorGoalCell
                && memory.motorTick < memory.corridorCheckTick + CORRIDOR_RECHECK_TICKS) {
            return memory.corridorClear;
        }
        memory.corridorGoalCell = goalCell;
        memory.corridorCheckTick = memory.motorTick;
        memory.corridorClear = CorridorProbe.clear(world, p.self().position(),
                t.position(), dropBudget);
        return memory.corridorClear;
    }

    /**
     * True when the target sits on a different STANDING level than the boxer and
     * is genuinely out of reach — on the ground-snapped gap, and only after it
     * has persisted {@link #ELEVATION_GAP_PERSIST_TICKS} consecutive ticks
     * (ballistic transients die inside the window). Gated on TRUE 3D range (a
     * target 3 blocks straight up reads horizontal distance ~0) and on not
     * actively retreating (orbits are tangential, dot ≈ 0, and must still plan;
     * a fleeing heal/food routine, dot ≈ −1, must not).
     */
    private boolean needsElevationRoute(Perception p, Vec3d desired) {
        Perception.TargetState t = p.target();
        if (t == null || memory.elevationGapTicks < ELEVATION_GAP_PERSIST_TICKS) {
            return false;
        }
        double gap = memory.targetGroundGap;
        if (t.distance() * t.distance() + gap * gap <= 4.0) {
            return false; // within true 3D reach of the pocket — no route needed
        }
        Vec3d toTarget = new Vec3d(t.x() - p.self().x(), 0.0, t.z() - p.self().z())
                .horizontalNormalized();
        return desired.horizontalNormalized().dot(toTarget) > -0.25;
    }

    /** Search-box half-width for an elevation plan: the goal plus a stair-hunting
     *  margin, never below the planner default, capped at the planner's Folia bound. */
    static int elevationExtent(int chebyshevCells) {
        return Math.min(BaritoneStylePlanner.MAX_EXTENT_CAP,
                Math.max(BaritoneStylePlanner.MAX_EXTENT, chebyshevCells + ELEVATION_EXTENT_MARGIN));
    }

    /** Abandon an in-flight search whose goal cell drifted beyond the box margin. */
    private void invalidateStaleSearch(Perception p) {
        if (memory.search == null) {
            return;
        }
        Perception.TargetState t = p.target();
        if (t == null || memory.search.goalDriftCells(
                (int) Math.floor(t.x()), (int) Math.floor(t.z())) > SEARCH_DRIFT_CELLS) {
            memory.search = null;
        }
    }

    /**
     * Open a sliced search toward the target. Elevation searches get the
     * adaptive stair-hunting box at {@link #ELEVATION_PLAN_BUDGET}; rescue
     * passes keep the classic walk-only-then-jump policy at {@link #PLAN_BUDGET}
     * over the default box. Every search carries the goal's fall envelope so
     * planned drops obey the same budget steering enforces.
     */
    private void beginSearch(Perception p, CollisionView world, int kind, double dropBudget) {
        Perception.TargetState t = p.target();
        if (t == null) {
            return;
        }
        Perception.SelfState self = p.self();
        BaritoneStylePlanner.FallBudget fall = new BaritoneStylePlanner.FallBudget(
                dropBudget, self.safeFallDistance(),
                FallDamage.damageFactor(self.fallEpf(), self.fallDamageMultiplier(),
                        self.slowFalling()));
        Vec3d position = self.position();
        if (kind == SEARCH_ELEVATION) {
            int cheb = Math.max(
                    Math.abs((int) Math.floor(t.x()) - (int) Math.floor(position.x())),
                    Math.abs((int) Math.floor(t.z()) - (int) Math.floor(position.z())));
            memory.search = planner.begin(position, t.position(), world, true,
                    elevationExtent(cheb), fall, ELEVATION_PLAN_BUDGET);
        } else {
            memory.search = planner.begin(position, t.position(), world,
                    kind == SEARCH_RESCUE_JUMP, BaritoneStylePlanner.MAX_EXTENT, fall,
                    PLAN_BUDGET);
        }
        memory.searchKind = kind;
    }

    /**
     * Advance the in-flight search one slice; on completion, adopt its route
     * under the opener's policy (elevation: the Y-progress partial gate + climb
     * latch at the route's OWN end level; rescue: plain adoption, with the
     * walk-only pass rolling over to a jump pass when it finds nothing —
     * exactly the old two-pass rescue, sliced). While the search is in flight
     * this returns without output and steering keeps the boxer moving.
     */
    private void stepSearch(Perception p, CollisionView world, double dropBudget) {
        BaritoneStylePlanner.SearchState search = memory.search;
        if (search == null) {
            return;
        }
        memory.lastSearchStepTick = memory.motorTick;
        Optional<BaritoneStylePlanner.Route> verdict =
                planner.step(search, world, SEARCH_SLICE_EXPANSIONS);
        if (!search.done()) {
            return; // still in flight — resume next tick
        }
        int kind = memory.searchKind;
        memory.search = null;
        memory.searchKind = 0;
        Perception.TargetState t = p.target();
        if (t == null) {
            return;
        }
        if (verdict.isEmpty() || verdict.get().waypoints().isEmpty()) {
            if (kind == SEARCH_RESCUE_WALK) {
                // No way AROUND on foot: retry with jumps (stepped next tick).
                beginSearch(p, world, SEARCH_RESCUE_JUMP, dropBudget);
            }
            return;
        }
        BaritoneStylePlanner.Route route = verdict.get();
        if (kind == SEARCH_ELEVATION && !route.complete()
                && Math.abs(t.y() - route.endFloorY())
                        > Math.abs(t.y() - p.self().y()) - 0.5) {
            return; // an under-target dead-end breadcrumb — steer reactively instead
        }
        memory.path = route.waypoints();
        memory.pathCursor = 0;
        memory.waypointTicks = 0;
        memory.pathOrigin = route.origin();
        memory.lastGoalCell = cell(t.x(), t.z());
        memory.climbLatch = kind == SEARCH_ELEVATION;
        if (memory.climbLatch) {
            // The latch releases at the route's OWN end level — never the
            // target's instantaneous (possibly airborne) y, an unreachable
            // number that would pin a stale route for seconds (the ghost-chase).
            memory.climbGoalY = route.endFloorY();
        }
    }

    /**
     * Follow the committed route: consume every waypoint already passed
     * (monotonic — the nearest-point-ahead join; never backwards), steer at a
     * look-ahead point along the polyline, and hand the drop budget through to
     * steering so a planned descent is never vetoed at its own lip. Returns
     * {@code null} — the caller drops the route — when exhausted, when a
     * flat-pursuit target changed cells, or when the stall clock runs out.
     * A latched climb ignores target cell changes (the replacement search
     * handles those) and releases once the feet reach the route's end level.
     * An ASCEND step to the next waypoint arms ProactiveJump's scheduled
     * takeoff exactly as before.
     */
    private MoveHeading followRoute(Perception p, CollisionView world, double dropBudget) {
        List<Vec3d> path = memory.path;
        if (path == null || memory.pathCursor >= path.size()) {
            return null;
        }
        if (memory.climbLatch
                && Math.abs(p.self().y() - memory.climbGoalY) <= CLIMB_ARRIVED) {
            memory.climbLatch = false; // arrived at the route's end level — normal rules resume
        }
        Perception.TargetState t = p.target();
        if (!memory.climbLatch
                && (t == null || cell(t.x(), t.z()) != memory.lastGoalCell)) {
            return null; // flat pursuit: the target moved to a different cell — replan from scratch
        }
        // Monotonic join: advance past EVERY waypoint already behind the boxer,
        // so a route adopted mid-motion (or an overshot drop landing) is joined
        // at the nearest point ahead instead of steering backwards onto history.
        while (memory.pathCursor < path.size()
                && waypointConsumed(p.self(),
                        memory.pathCursor == 0 ? memory.pathOrigin
                                : path.get(memory.pathCursor - 1),
                        path.get(memory.pathCursor),
                        memory.pathCursor + 1 < path.size()
                                ? path.get(memory.pathCursor + 1) : null)) {
            memory.pathCursor++;
            memory.waypointTicks = 0;
        }
        if (memory.pathCursor >= path.size()) {
            return null;
        }
        if (++memory.waypointTicks > WAYPOINT_STALL_TICKS) {
            return null; // a waypoint we cannot reach — the route is stale geometry now
        }
        Vec3d self = p.self().position();
        Vec3d prev = memory.pathCursor == 0 ? memory.pathOrigin
                : path.get(memory.pathCursor - 1);
        Vec3d steerAt = lookaheadPoint(self, prev, path, memory.pathCursor, ROUTE_LOOKAHEAD);
        Vec3d heading = steerAt.subtract(self).horizontalNormalized();
        if (heading.horizontalDistanceSqr() < 1.0E-8) {
            return null;
        }
        Vec3d waypoint = path.get(memory.pathCursor);
        double rise = waypoint.y() - p.self().y();
        if (rise > ClientPhysics.STEP_HEIGHT && rise <= NavGeometry.MAX_JUMP_RISE + 1.0E-6) {
            // Scheduled ASCEND takeoff: hand ProactiveJump the step-face distance —
            // the waypoint cell's near face sits half a cell before its centre, and
            // the box's leading edge a server half-width before the body centre.
            Vec3d toWaypoint = waypoint.subtract(self);
            memory.routeStepFace = Math.max(0.0,
                    Math.sqrt(toWaypoint.horizontalDistanceSqr())
                            - 0.5 - ClientPhysics.PLAYER_WIDTH / 2.0);
            memory.routeStepRise = rise;
        }
        return steering.steer(p, heading, world, dropBudget);
    }

    /**
     * Whether waypoint {@code wp} (whose approach segment starts at {@code prev})
     * counts as passed for the boxer at {@code self}. Classified by the segment's
     * elevation change:
     * <ul>
     *   <li><b>ASCEND</b> (rise beyond an auto-step): strict stand-on — grounded,
     *   within a block horizontally, feet within a step of its level. Horizontal
     *   proximity alone must never advance it: a boxer under a platform passes
     *   within a block of every elevated waypoint above (eating the climbing
     *   tail), and a mid-jump flyby would redirect the heading into the riser's
     *   face before the feet land.</li>
     *   <li><b>DROP</b> (fall beyond an auto-step): grounded at (or below) the
     *   validated landing level — air control stays pointed at the landing
     *   column until touchdown, never redirected mid-fall — consumed in place
     *   OR on overshoot: a sprint walk-off carries 2.5-4.5 blocks past the
     *   lip, so "the NEXT waypoint is already nearer than the landing cell"
     *   consumes too, instead of steering backwards.</li>
     *   <li><b>LEVEL</b>: consumed once the boxer crosses the waypoint's
     *   along-segment plane ({@code toWaypoint · segmentDir ≤ 0}) — corner cuts
     *   and knock-pasts advance the cursor instead of reversing.</li>
     * </ul>
     * Package-visible and pure for direct unit pinning.
     */
    static boolean waypointConsumed(Perception.SelfState self, Vec3d prev, Vec3d wp,
            @Nullable Vec3d next) {
        double dy = wp.y() - prev.y();
        double dx = wp.x() - self.x();
        double dz = wp.z() - self.z();
        double horizontalSq = dx * dx + dz * dz;
        if (dy > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
            return horizontalSq < WAYPOINT_REACHED_SQ && self.onGround()
                    && Math.abs(self.y() - wp.y()) <= ClientPhysics.STEP_HEIGHT + 1.0E-6;
        }
        if (dy < -ClientPhysics.STEP_HEIGHT - 1.0E-6) {
            if (!self.onGround()
                    || self.y() - wp.y() > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
                return false;
            }
            if (horizontalSq < WAYPOINT_REACHED_SQ) {
                return true;
            }
            if (next == null) {
                return false;
            }
            double nx = next.x() - self.x();
            double nz = next.z() - self.z();
            return nx * nx + nz * nz < horizontalSq;
        }
        Vec3d segDir = wp.subtract(prev).horizontalNormalized();
        if (segDir.horizontalDistanceSqr() < 1.0E-8) {
            return horizontalSq < WAYPOINT_REACHED_SQ; // degenerate segment — radius rule
        }
        return dx * segDir.x() + dz * segDir.z() <= 1.0E-9;
    }

    /**
     * The steer point: the boxer's projection onto the current segment, marched
     * {@code lookahead} blocks forward along the polyline. The march never
     * crosses a non-LEVEL waypoint — an ascend's riser and a drop's validated
     * landing column must be aimed at directly, not cut — so those return the
     * waypoint itself. Package-visible and pure for direct unit pinning.
     */
    static Vec3d lookaheadPoint(Vec3d self, Vec3d prev, List<Vec3d> path, int cursor,
            double lookahead) {
        Vec3d wp = path.get(cursor);
        if (Math.abs(wp.y() - prev.y()) > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
            return wp; // ascend riser / drop landing: steer at it directly
        }
        Vec3d segDir = wp.subtract(prev).horizontalNormalized();
        double along = 0.0;
        if (segDir.horizontalDistanceSqr() >= 1.0E-8) {
            double segLen = Math.sqrt(wp.subtract(prev).horizontalDistanceSqr());
            along = Math.max(0.0, Math.min(segLen,
                    (self.x() - prev.x()) * segDir.x() + (self.z() - prev.z()) * segDir.z()));
        }
        Vec3d point = new Vec3d(prev.x() + segDir.x() * along, wp.y(),
                prev.z() + segDir.z() * along);
        double remaining = lookahead;
        int i = cursor;
        while (remaining > 0.0) {
            Vec3d target = path.get(i);
            double dx = target.x() - point.x();
            double dz = target.z() - point.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist >= remaining) {
                double f = remaining / Math.max(dist, 1.0E-9);
                return new Vec3d(point.x() + dx * f, target.y(), point.z() + dz * f);
            }
            point = target;
            remaining -= dist;
            if (i + 1 >= path.size()
                    || Math.abs(path.get(i + 1).y() - target.y())
                            > ClientPhysics.STEP_HEIGHT + 1.0E-6) {
                return target; // route end, or the next step is a riser/drop
            }
            i++;
        }
        return point;
    }

    private static long cell(double x, double z) {
        return (((long) Math.floor(x)) << 32) ^ (((long) Math.floor(z)) & 0xFFFFFFFFL);
    }
}

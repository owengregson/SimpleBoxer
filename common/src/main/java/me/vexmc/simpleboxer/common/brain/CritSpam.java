package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.brain.Intent.JumpHint;
import me.vexmc.simpleboxer.common.physics.Box;
import me.vexmc.simpleboxer.common.physics.ClientPhysics;
import me.vexmc.simpleboxer.common.physics.CollisionView;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Decides the ceiling crit-spam rhythm — the low-roof "bonk hop" a boxing-arena
 * sweat rides for 1.5× hits. On every supported server the crit decision is made
 * inside NMS {@code Player.attack}: full attack meter, {@code fallDistance > 0},
 * {@code !onGround}, {@code !isSprinting} (plus not climbing/in-water/blind/
 * passenger). {@code fallDistance} accumulates server-side from the boxer's own
 * genuine move packets only while it moves DOWN off the ground, so of a hop only
 * the post-apex descending ticks are crit-eligible. Under a low ceiling the
 * 0.42-impulse jump arc is clipped early — in the canonical 3-block room
 * (1.2 blocks of headroom over the 1.8 box) the integrator bonks at tick 5,
 * descends ticks 6–10 and lands on tick 11, so roughly half of every hop is a
 * crit window versus about a quarter of a full open-air jump.
 *
 * <p>Three outputs, all riding existing seams (no new wire behavior):
 * a PULSED jump key, pressed only on grounded ticks — the airborne release is
 * what resets {@code ClientPhysics}' vanilla {@code noJumpDelay = 10} hold rule,
 * so the re-press launches on the first landed tick instead of eating the
 * held-key tax; a sprint drop for the descending click window, because crits
 * require {@code !isSprinting} when the server processes the attack (OCM
 * included — it pins the attack meter full but never patches the NMS crit
 * gate); and a per-tick active stamp {@link ClickController} reads to withhold
 * attacks outside the window (the arm still swings — the CPS clock is never
 * bent).</p>
 *
 * <p>Pure and owning-thread: state lives in the {@link BrainMemory} scratch,
 * geometry comes from the live {@link CollisionView} exactly like
 * {@link ProactiveJump}. The module gates itself on the engage goal owning the
 * tick, so routines (pot/rod/food) and the w-tap release window are never
 * fought over the jump key or the sprint flag.</p>
 */
public final class CritSpam {

    /** Scratch id for the eligibility latch + per-tick active stamp. */
    static final String MEM_ID = "critSpam";
    /** Scratch slot: 1 while the grounded ceiling probe last read crit-eligible. */
    static final int SLOT_ELIGIBLE = 0;
    /** Scratch slot: {@code serverTick + 1} of the last active tick (0 = never). */
    static final int SLOT_ACTIVE_STAMP = 1;
    static final int SCRATCH_SIZE = 2;

    /** The goal whose tick this module may ride; every other winner deactivates it. */
    private static final String ENGAGE_GOAL_ID = "engage";

    /**
     * Headroom band (from {@link NavGeometry#ceilingGap}) that makes a roof a
     * crit roof. Upper bound: the open-air apex is +1.2523 blocks, so any gap
     * below ~1.25 clips the arc early and shortens the crit-dead rising phase.
     * Lower bound: with gap g the descent lands once the cumulative fall
     * (0.0784, 0.2336, 0.4641, …) exceeds g, so g ≥ 0.25 keeps at least ~3
     * descending ticks per hop — below that the hop is all bonk and no fall,
     * and fallDistance barely registers.
     */
    public static final double CRIT_GAP_MIN = 0.25;
    public static final double CRIT_GAP_MAX = 1.25;

    /** How far past configured reach the target may sit and still be "in the pocket". */
    public static final double MELEE_BAND = 1.0;

    /**
     * Minimum configured CPS for the module to engage. The metronome fires every
     * 20/cps ticks and the widest crit window (gap 1.2) spans ~5 descending
     * ticks, so 4 CPS — one click per 5 ticks — is the slowest clock that still
     * lands a click in every full-height window. Below it a hop rhythm would be
     * theatre with no crits to show for the lost sprint pressure.
     */
    public static final double MIN_CPS = 4.0;

    /** Ms per tick — converts the {@code ping/2} action delay into tick counts. */
    private static final double MS_PER_TICK = 50.0;

    /** What the module wants this tick: a jump-key press and/or a sprint drop. */
    public record Decision(@NotNull JumpHint jump, boolean dropSprint) {

        public static final Decision NONE = new Decision(JumpHint.NONE, false);
    }

    /**
     * One decision tick, from the brain's motor stage at the same junction that
     * consults {@link ProactiveJump}. Never emits movement or clicks itself —
     * only the jump-key pulse, the sprint drop, and the scratch stamp the click
     * controller reads later in the same brain tick.
     *
     * @param p        the matured per-tick snapshot
     * @param goalId   id of the goal that won arbitration this tick
     * @param settings the live behavior profile
     * @param world    the live collision view (motor-stage geometry, like
     *                 {@link ProactiveJump})
     * @param mem      owning-thread scratchpad (holds the latch and the stamp)
     */
    public @NotNull Decision evaluate(@NotNull Perception p,
                                      @NotNull String goalId,
                                      @NotNull BoxerSettings settings,
                                      @NotNull CollisionView world,
                                      @NotNull BrainMemory mem) {
        int[] scratch = mem.ints(MEM_ID, SCRATCH_SIZE);
        // Off, under-clicked, pre-empted, out of the pocket, or mid w-tap
        // release: fully inactive. (The release window already lifted forward
        // and sprint for its own rhythm — the techniques must not fight.)
        if (!settings.critSpam().enabled()
                || settings.cps() < MIN_CPS
                || !ENGAGE_GOAL_ID.equals(goalId)
                || mem.wtapReleaseLeft > 0
                || !p.hasTarget()
                || p.target().distance() > settings.reach() + MELEE_BAND) {
            scratch[SLOT_ELIGIBLE] = 0;
            return Decision.NONE;
        }

        Perception.SelfState self = p.self();
        // The roof probe is only meaningful from the floor — mid-hop the box has
        // already risen into the gap it would be measuring. Re-check on grounded
        // ticks and latch the verdict across the airborne half of the cycle.
        if (self.onGround()) {
            Box box = NavGeometry.playerBox(self.x(), self.y(), self.z());
            double gap = NavGeometry.ceilingGap(world, box);
            scratch[SLOT_ELIGIBLE] = gap >= CRIT_GAP_MIN && gap <= CRIT_GAP_MAX ? 1 : 0;
        }
        if (scratch[SLOT_ELIGIBLE] == 0) {
            return Decision.NONE;
        }

        // Active: stamp the tick so the click controller holds attacks to the
        // crit window later in this same brain tick.
        scratch[SLOT_ACTIVE_STAMP] = (int) p.combat().serverTick() + 1;

        // PULSE the jump key: pressed only while grounded. The airborne release
        // is load-bearing — ClientPhysics resets noJumpDelay to 0 on a released
        // key (vanilla aiStep), so the re-press on the first landed tick
        // launches immediately instead of eating the 10-tick held-key delay.
        JumpHint jump = self.onGround() ? JumpHint.JUMP : JumpHint.NONE;

        // Crits require !isSprinting when the server processes the attack, so
        // sprint drops exactly for the descending click window — MIN_CPS pins
        // the metronome tight enough that a click is always imminent inside it —
        // and re-arms the moment the boxer lands or rises. Never a permanent
        // desprint: the condition cannot hold on a grounded tick.
        boolean dropSprint = critWindowAtArrival(p);

        return new Decision(jump, dropSprint);
    }

    /**
     * Whether the module was active on {@code serverTick} — stamped by
     * {@link #evaluate} at the motor junction, read by {@link ClickController}
     * when the CPS clock fires later in the same brain tick. A zeroed scratch
     * (never stamped) matches no tick.
     */
    public static boolean activeThisTick(@NotNull BrainMemory mem, long serverTick) {
        return mem.ints(MEM_ID, SCRATCH_SIZE)[SLOT_ACTIVE_STAMP] == (int) serverTick + 1;
    }

    /**
     * Whether the boxer's OWN vertical phase is crit-eligible when a click
     * decided this tick reaches the server: airborne and descending. The press
     * rides the action line ~ping/2 before the server handles it, so own vy is
     * extrapolated by that lag under vanilla gravity — the same linear ping/2
     * lead {@link ClickController} already gives the target's position.
     * Airborne-now approximates airborne-at-arrival: at arena pings the lag is
     * a fraction of a hop, and a landing inside it only costs a withheld attack.
     */
    public static boolean critWindowAtArrival(@NotNull Perception p) {
        if (p.self().onGround()) {
            return false;
        }
        double predictTicks = (p.pingMs() / 2.0) / MS_PER_TICK;
        double vyAtArrival = p.self().velocity().y() - ClientPhysics.GRAVITY * predictTicks;
        return vyAtArrival < 0.0;
    }
}

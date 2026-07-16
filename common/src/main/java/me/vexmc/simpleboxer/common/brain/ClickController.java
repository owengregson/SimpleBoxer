package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TargetState;
import me.vexmc.simpleboxer.common.combat.ClickScheduler;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;

/**
 * Feature 17 — click management. Turns the CPS clock into concrete
 * {@link ActionIntent}s for one tick, gating the <em>attack</em> (not the swing)
 * behind reach and an aim cone. Two touches of realism:
 *
 * <ul>
 *   <li><b>Latency-aware reach.</b> A click lands on the server after roughly
 *       {@code ping/2} of travel, so we hit-test against where the target
 *       <em>will be</em> then — {@code pos + velocity·(ping/2/50 ticks)} — not
 *       where we perceived it. A defender backpedalling out of range is judged
 *       on the extrapolated position, which is what the server will see.</li>
 *   <li><b>Whiff still swings.</b> A click that fails reach/aim/miss-roll still
 *       emits a bare {@link ActionIntent#swing()} — a real client always swings
 *       the arm on the mouse press, hit or not. When the attack does land, it is
 *       added <em>before</em> the swing so the wire order matches vanilla
 *       (attack packet, then the arm-swing animation).</li>
 *   <li><b>Crit-window hold.</b> While {@link CritSpam} is active this tick the
 *       attack (never the swing) is additionally withheld unless the boxer will
 *       be descending and airborne when the press arrives — concentrating landed
 *       hits where the server pays critical damage, without bending the click
 *       cadence or the rng stream.</li>
 * </ul>
 *
 * Pure and stateless: the only mutable seam is {@link ClickScheduler} (the CPS
 * clock, owned by the caller) and {@link BrainMemory#rng} (for the deliberate
 * miss roll) — no {@code Math.random}, so identical boxers whiff identically.
 */
public final class ClickController {

    /** Vanilla eye height above the feet, in blocks. */
    private static final double EYE_HEIGHT = 1.62;

    /** Aim point on the target: chest, above its feet, in blocks. */
    private static final double CHEST_HEIGHT = 0.9;

    /** Ms per tick — converts the {@code ping/2} action delay into tick counts. */
    private static final double MS_PER_TICK = 50.0;

    /**
     * Consider firing a click this tick, appending 0..2 actions to {@code out}.
     *
     * @param p               the matured per-tick snapshot
     * @param aimYawDeg        where the crosshair actually points this tick
     * @param reach            attack reach in blocks (3D)
     * @param aimToleranceDeg  half-angle of the aim cone the attack must fall in
     * @param suppressed       true while mid item-use (block/eat/rod hold): no click
     * @param missChance       probability in [0,1] of deliberately whiffing an
     *                         otherwise-valid attack (rolled on {@code mem.rng})
     * @param clock            the CPS clock; a click fires only when it says so
     * @param nowMs            monotonic wall clock for {@code clock}
     * @param mem              owning-thread scratchpad (its {@code rng} drives misses)
     * @param out              sink for this tick's actions, in wire order
     */
    public void consider(@NotNull Perception p,
                         float aimYawDeg,
                         double reach,
                         double aimToleranceDeg,
                         boolean suppressed,
                         double missChance,
                         @NotNull ClickScheduler clock,
                         long nowMs,
                         @NotNull BrainMemory mem,
                         @NotNull List<ActionIntent> out) {
        // The CPS clock is the metronome: nothing happens on a tick it stays silent.
        if (!clock.shouldClick(nowMs)) {
            return;
        }
        // Mid block/eat/rod-hold the mouse press belongs to the item, not a swing.
        if (suppressed) {
            return;
        }

        if (p.hasTarget()) {
            TargetState target = p.target();
            SelfState self = p.self();

            // Extrapolate to click-landing: the press reaches the server ~ping/2
            // later, so gate on where the target will be, not where we saw it.
            double predictTicks = (p.pingMs() / 2.0) / MS_PER_TICK;
            Vec3d predicted = target.position().add(target.velocity().scale(predictTicks));

            double dx = predicted.x() - self.x();
            double dy = (predicted.y() + CHEST_HEIGHT) - (self.y() + EYE_HEIGHT);
            double dz = predicted.z() - self.z();

            boolean inReach = (dx * dx + dy * dy + dz * dz) <= reach * reach;

            // Vanilla bearing for a direction (dx,dz); compare against the live aim.
            double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
            boolean aimed = Math.abs(wrapDegrees(desiredYaw - aimYawDeg)) <= aimToleranceDeg;

            // Roll the miss ONLY once the hit is otherwise valid, so the rng stream
            // stays undisturbed on out-of-reach/off-aim ticks (deterministic misses).
            boolean lands = inReach && aimed
                    && (missChance <= 0.0 || mem.rng.nextDouble() >= missChance);
            // Crit-spam hold: while the hop module owns the rhythm, an otherwise
            // valid click on a rising/grounded tick withholds the ATTACK — a
            // deliberate whiff, the arm still swings below, the CPS clock is
            // untouched — so landed hits concentrate in the descending window
            // where the server pays the 1.5×. Own vertical phase is judged at
            // packet arrival, the same ping/2 extrapolation the target got. The
            // roll above stays first so the rng stream is identical either way.
            if (lands && CritSpam.activeThisTick(mem, p.combat().serverTick())
                    && !CritSpam.critWindowAtArrival(p)) {
                lands = false;
            }
            if (lands) {
                out.add(ActionIntent.attack());
            }
        }

        // A click always swings the arm — a whiff, an empty click, or a real hit.
        out.add(ActionIntent.swing());
    }

    /** Fold a degree delta into (-180, 180]; mirrors {@code AimSpring.wrapDegrees}. */
    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }
}

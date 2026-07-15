package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.AdaptiveStrafe;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * The default combat movement: chase the target, hold a pocket, circle-strafe, or
 * weave — chosen by the configured {@link BoxerSettings.Movement.Style} — plus the
 * w-tap / s-tap sprint-reset rhythm. This ONE goal covers RUSH / STRAFE_CIRCLE /
 * STRAFE_WEAVE; routines (heal, rod, blockhit, food) sit ABOVE it in the arbiter
 * and pre-empt it when their situation fires. It only produces a world-space move
 * direction and a facing — the brain's motor stage makes that collision-smart.
 */
public final class EngageGoal implements Goal {

    /** A moderate base so a triggered routine can out-score ordinary engagement. */
    public static final double BASE_UTILITY = 0.5;
    private static final double DEFAULT_ORBIT_RADIUS = 2.75;

    /**
     * How far outside the ring the boxer already circles while closing. Wider
     * than the old {@code 1.5} so it sidesteps from ~5.75 blocks rather than only
     * once inside ~4.25 — the approach itself is a strafe, not a straight rush.
     */
    private static final double ORBIT_ENGAGE_MARGIN = 3.0;

    /**
     * In-band forward pulse. Much smaller than the old {@code 0.6} so the ring
     * heading stays strongly tangential (a real circle) instead of being diluted
     * toward the target — sprint still re-arms on the pulse ticks.
     */
    private static final double ORBIT_INBAND_FORWARD = 0.35;

    private final Supplier<BoxerSettings> settings;
    private final AdaptiveStrafe strafe;

    public EngageGoal(@NotNull Supplier<BoxerSettings> settings, @NotNull AdaptiveStrafe strafe) {
        this.settings = settings;
        this.strafe = strafe;
    }

    @Override
    public @NotNull String id() {
        return "engage";
    }

    @Override
    public double utility(@NotNull Perception p) {
        if (!p.hasTarget()) {
            return 0.0;
        }
        return settings.get().movement().style() == BoxerSettings.Movement.Style.STAND
                ? 0.0 : BASE_UTILITY;
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        BoxerSettings s = settings.get();
        Perception.TargetState t = p.target();
        if (t == null) {
            return Intent.IDLE;
        }
        Perception.SelfState self = p.self();
        Intent.FacingIntent facing = Intent.FacingIntent.aimAt(t.x(), t.eyeY() - 0.4, t.z());

        // The w-tap / s-tap release window: after a landed hit the forward key
        // lifts for a few ticks (dropping sprint), then re-presses — the packet
        // rhythm that re-arms sprint-extra knockback. Face the target throughout.
        boolean sTap = s.combat().sTap();
        BoxerSettings.WTap wtap = s.wtap();
        if (mem.wtapCountdown > 0) {
            mem.wtapCountdown--;
        } else if (mem.wtapCountdown == 0) {
            mem.wtapCountdown = -1;
            mem.wtapReleaseLeft = wtap.releaseTicks();
        }
        if (mem.wtapReleaseLeft > 0) {
            mem.wtapReleaseLeft--;
            // Last release tick: forward re-presses (sprint re-arms) NEXT tick, when
            // orbit resumes — flag it so adaptive strafing can land a juke on it.
            if (mem.wtapReleaseLeft == 0) {
                mem.wtapRepressed = true;
            }
            return new Intent(Vec3d.ZERO, facing, Intent.ActionIntent.none(), false, Intent.JumpHint.NONE);
        }

        Vec3d toTarget = new Vec3d(t.x() - self.x(), 0.0, t.z() - self.z()).horizontalNormalized();
        if (toTarget.lengthSqr() < 1.0E-8) {
            toTarget = new Vec3d(0.0, 0.0, 1.0); // degenerate overlap; pick a stable heading
        }
        double stop = s.movement().stopDistance();
        BoxerSettings.Movement.Style style = s.movement().style();

        // s-tap forces a straight-line combo — no A/D strafing — regardless of style.
        Vec3d moveDir;
        boolean sprint = s.movement().sprint();
        if (sTap || style == BoxerSettings.Movement.Style.RUSH) {
            moveDir = pocketAdjustedRush(toTarget, t.distance(), stop);
            sprint = sprint && moveDir.dot(toTarget) > 0.1; // no sprint while backing off
        } else if (style == BoxerSettings.Movement.Style.STRAFE_CIRCLE) {
            moveDir = orbit(p, s, toTarget, t.distance(), stop, mem);
        } else { // STRAFE_WEAVE
            AdaptiveStrafe.StrafeDecision weave =
                    strafe.next(p, AdaptiveStrafe.StrafeMode.WEAVE, false, mem);
            moveDir = toTarget.add(tangent(toTarget, weave.sign()).scale(0.6)).horizontalNormalized();
        }

        return new Intent(moveDir, facing, Intent.ActionIntent.none(), sprint, Intent.JumpHint.NONE);
    }

    /** Rush the target; hold or ease back only when a real stop-ring is set. */
    private static Vec3d pocketAdjustedRush(Vec3d toTarget, double distance, double stop) {
        if (stop > 0.0 && distance <= stop) {
            return distance < stop - 0.8 ? toTarget.scale(-1.0) : Vec3d.ZERO;
        }
        return toTarget;
    }

    /**
     * Circle the target at a real orbit radius. Tangential most ticks (a true
     * circle, not the old forward-into-the-target spiral); the forward key is
     * duty-cycled — pressed to close when outside the ring, released to hold it,
     * back-pedalled when too close — so sprint stays re-armed without spiralling in.
     */
    private Vec3d orbit(Perception p, BoxerSettings s, Vec3d toTarget, double distance,
            double stop, BrainMemory mem) {
        double radius = Math.max(DEFAULT_ORBIT_RADIUS, stop);
        // Approach phase: only far outside the (widened) ring do we rush straight.
        if (distance > radius + ORBIT_ENGAGE_MARGIN) {
            return toTarget;
        }
        AdaptiveStrafe.StrafeDecision sd =
                strafe.next(p, AdaptiveStrafe.StrafeMode.ORBIT, s.combat().adaptiveStrafe(), mem);
        Vec3d tangent = tangent(toTarget, sd.sign());
        double radiusError = distance - radius;
        double forwardBias;
        if (radiusError > 0.6) {
            forwardBias = 1.0; // outside the ring: close in (but already strafing)
        } else if (radiusError < -0.6) {
            forwardBias = -0.8; // inside the ring: back off
        } else {
            // In the band: hold the ring, pulsing a SMALL forward to keep sprint legal
            // without diluting the tangential authority into a spiral.
            int[] cycle = mem.ints("engageOrbit", 1);
            cycle[0] = (cycle[0] + 1) % 5;
            forwardBias = cycle[0] < 2 ? ORBIT_INBAND_FORWARD : 0.0;
        }
        return tangent.add(toTarget.scale(forwardBias)).horizontalNormalized();
    }

    /** Rotate a horizontal unit vector 90° about +Y: sign +1 = left (CCW), -1 = right. */
    private static Vec3d tangent(Vec3d dir, int sign) {
        return sign >= 0
                ? new Vec3d(-dir.z(), 0.0, dir.x())
                : new Vec3d(dir.z(), 0.0, -dir.x());
    }

    @Override
    public int minDwellTicks() {
        return 4; // don't re-plan the engage micro-decision every tick
    }

    @Override
    public double commitBonus() {
        return 0.05;
    }

    @Override
    public boolean mayLeaveLedges() {
        return true; // pursuit walks off edges toward the target, like a real client
    }
}

package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rod-poke (feature 12): when an opponent is rushing in, swap to a fishing rod,
 * cast the hook to knock it back, then swap straight back to the weapon and let
 * {@link EngageGoal} combo into the opening. A pure sprint-rusher walks into the
 * hook; the knockback buys the tempo a melee combo needs.
 *
 * <p>Opt-in via {@code combat().rodKnockback()}. It out-scores ordinary
 * {@link EngageGoal} engagement (0.5) only in a narrow window: the target sits in
 * the configured rod band and is closing FAST (its velocity has a real component
 * pointed at the boxer). One poke arms a short cooldown so the boxer does not
 * spam the rod — during the cooldown {@link #utility(Perception)} reads 0 and
 * Engage resumes for the melee follow-up.</p>
 *
 * <p>State lives in {@code mem.ints("rodPoke", 2) = {phase, cooldown}}. The three
 * cast phases (raise → cast → swap-back) each emit ONE {@link Intent.ActionIntent}
 * on consecutive ticks so the latency line preserves their order. Because a Goal
 * is built per-boxer with a single per-boxer {@link BrainMemory}, the goal caches
 * that memory on the first {@code decide} so the mem-resident cooldown counter is
 * also visible to the (perception-only) {@code utility} signature.</p>
 */
public final class RodPokeGoal implements Goal {

    /** Beats {@link EngageGoal#BASE_UTILITY} (0.5) so a live poke pre-empts melee. */
    public static final double POKE_UTILITY = 0.75;
    /** Minimum inbound-velocity component (blocks/tick) toward the boxer to fire. */
    public static final double CLOSING_THRESHOLD = 0.08;
    /** Ticks the routine stays quiet after a poke, so Engage can combo the opening. */
    public static final int COOLDOWN_TICKS = 30;

    private static final String STATE_ID = "rodPoke";
    private static final int PHASE = 0;
    private static final int COOLDOWN = 1;

    private final Supplier<BoxerSettings> settings;

    /**
     * The owning boxer's scratchpad, captured on the first {@code decide}. Lets
     * the perception-only {@code utility} read the mem-resident cooldown counter.
     * Null until the goal has decided at least once (treated as "off cooldown").
     */
    private @Nullable BrainMemory owned;

    public RodPokeGoal(@NotNull Supplier<BoxerSettings> settings) {
        this.settings = settings;
    }

    @Override
    public @NotNull String id() {
        return "rodPoke";
    }

    @Override
    public double utility(@NotNull Perception p) {
        BoxerSettings.Combat c = settings.get().combat();
        if (!c.rodKnockback() || !p.inv().hasRod() || !p.hasTarget()) {
            return 0.0;
        }
        // Mid-sequence override: once the cast FSM has left idle (phase != 0), keep a
        // positive score REGARDLESS of the band/closing/cooldown gates below, so the
        // arbiter does not forfeit our dwell mid-cast. The poke's OWN hook-knockback
        // reverses the target's velocity (and opens the range), which would otherwise
        // trip the closing gate and hand control to Engage before the swap-back-to-
        // weapon + cooldown-arm phase ever runs — stranding the boxer swinging the rod
        // uselessly in melee. Phase is READ from the owning boxer's mem here (never
        // mutated); it is advanced only in decide().
        if (phase() != 0) {
            return POKE_UTILITY;
        }
        Perception.TargetState t = p.target();
        double dist = t.distance();
        if (dist < c.rodMin() || dist > c.rodMax()) {
            return 0.0; // out of the rod band — let Engage carry the melee
        }
        // Closing test: the unit vector from the target toward the boxer, dotted
        // with the target's own velocity, is the inbound speed. A rusher scores
        // high; a target holding or backing off scores <= 0.
        Vec3d dirTargetToBoxer =
                new Vec3d(p.self().x() - t.x(), 0.0, p.self().z() - t.z()).horizontalNormalized();
        double closing = dirTargetToBoxer.dot(t.velocity());
        if (closing <= CLOSING_THRESHOLD) {
            return 0.0;
        }
        if (cooldown() > 0) {
            return 0.0; // recently poked — Engage resumes for the combo
        }
        return POKE_UTILITY;
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        this.owned = mem;
        int[] st = mem.ints(STATE_ID, 2);
        Perception.TargetState t = p.target();
        if (t == null) {
            return Intent.IDLE;
        }

        Intent.FacingIntent chest = Intent.FacingIntent.aimAt(t.x(), t.eyeY() - 0.4, t.z());

        // On cooldown: bleed it down and hold as idle (face the target, no action).
        // In practice utility reads 0 here so Engage wins; this keeps the FSM sane
        // if the routine is ever asked to decide while still cooling down.
        if (st[COOLDOWN] > 0) {
            st[COOLDOWN]--;
            st[PHASE] = 0;
            return new Intent(Vec3d.ZERO, chest, Intent.ActionIntent.none(), false, Intent.JumpHint.NONE);
        }

        Vec3d toTarget = new Vec3d(t.x() - p.self().x(), 0.0, t.z() - p.self().z())
                .horizontalNormalized();

        switch (st[PHASE]) {
            case 0 -> {
                // Raise the rod: select the rod slot, keep closing, aim at the chest.
                st[PHASE] = 1;
                return new Intent(toTarget, chest,
                        Intent.ActionIntent.selectSlot(settings.get().items().rodSlot()),
                        false, Intent.JumpHint.NONE);
            }
            case 1 -> {
                // Cast: right-click to fling the hook, aimed at the target.
                st[PHASE] = 2;
                Intent.FacingIntent atTarget = Intent.FacingIntent.aimAt(t.x(), t.eyeY(), t.z());
                return new Intent(toTarget, atTarget,
                        Intent.ActionIntent.startUse(true), false, Intent.JumpHint.NONE);
            }
            default -> {
                // Swap back to the weapon and arm the cooldown so Engage combos.
                st[PHASE] = 0;
                st[COOLDOWN] = COOLDOWN_TICKS;
                return new Intent(toTarget, chest,
                        Intent.ActionIntent.selectSlot(settings.get().items().weaponSlot()),
                        false, Intent.JumpHint.NONE);
            }
        }
    }

    /** Current cooldown ticks from the owning boxer's mem, or 0 before first decide. */
    private int cooldown() {
        return owned == null ? 0 : owned.ints(STATE_ID, 2)[COOLDOWN];
    }

    /** Current cast phase from the owning boxer's mem, or 0 (idle) before first decide. */
    private int phase() {
        return owned == null ? 0 : owned.ints(STATE_ID, 2)[PHASE];
    }

    @Override
    public int minDwellTicks() {
        return 3; // keep the raise → cast → swap-back sequence intact
    }

    @Override
    public boolean suppressesAttack() {
        return true; // no melee swing while holding/casting the rod
    }
}

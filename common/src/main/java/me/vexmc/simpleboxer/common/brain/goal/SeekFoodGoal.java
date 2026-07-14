package me.vexmc.simpleboxer.common.brain.goal;

import java.util.function.Supplier;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Goal;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Seek-food (feature 13): when {@link BoxerSettings.Hunger#natural()} is on and the
 * boxer has gotten hungry (mid-fight, with food on the belt), it disengages, backs
 * off far enough to eat safely, stands still and eats, then swaps its weapon back and
 * re-engages. A pure utility {@link Goal} — no Bukkit/NMS — that sits ABOVE
 * {@code EngageGoal} in the arbiter and pre-empts it only while eligible.
 *
 * <p><b>Utility &amp; hysteresis.</b> {@link #utility(Perception)} is a pure function of
 * the perceived hunger and inventory (it must be — the arbiter passes no memory). It is
 * zero unless natural hunger is on, food is carried, and there is a target. Above the
 * configured {@code eatThreshold} it stays a low latch plateau ({@value #LATCH_UTILITY})
 * that ordinary combat ({@code EngageGoal} = 0.5) out-scores, so the routine never
 * <em>newly</em> triggers there; once hunger falls to/below {@code eatThreshold} it jumps
 * to {@value #PEAK_UTILITY} (below a heal routine, so PotHeal wins if both fire) and the
 * routine takes control. Because {@link #exclusive(Perception)} is true for any positive
 * utility, the routine HARD-SEIZES control the whole way back up: eating pushes hunger
 * through the plateau band (still {@code > 0}, still exclusive) and only releases the
 * latch once the boxer is comfortably full ({@value #FULL_HUNGER}/20), where utility
 * returns to zero. That plateau IS the hysteresis — the boxer is never pulled off
 * mid-meal.</p>
 */
public final class SeekFoodGoal implements Goal {

    /** Peak utility once hunger has fallen to/below the eat threshold. */
    public static final double PEAK_UTILITY = 0.7;
    /**
     * The low "keep-eating" plateau held while hunger climbs from the eat threshold
     * back to full. Positive (so {@link #exclusive} latches) yet below ordinary combat
     * (0.5), so the routine never newly triggers above the eat threshold.
     */
    public static final double LATCH_UTILITY = 0.1;
    /** Hunger (0..20) at/above which the boxer is comfortably full and the latch releases. */
    public static final double FULL_HUNGER = 18.0;

    /** Back off until the target is at least this far before beginning to eat. */
    private static final double BACKOFF_DISTANCE = 4.0;
    /** Ticks to hold the eat before releasing (vanilla eat is ~32 ticks). */
    private static final int EAT_TICKS = 34;

    /* FSM phases stored in mem.ints("seekFood", 2) = { phase, eatTimer }. */
    private static final int PHASE_BACK_OFF = 0;
    private static final int PHASE_SWAP_FOOD = 1;
    private static final int PHASE_BEGIN_EAT = 2;
    private static final int PHASE_HOLD = 3;
    private static final int PHASE_SWAP_BACK = 4;

    private final Supplier<BoxerSettings> settings;

    public SeekFoodGoal(@NotNull Supplier<BoxerSettings> settings) {
        this.settings = settings;
    }

    @Override
    public @NotNull String id() {
        return "seekFood";
    }

    @Override
    public double utility(@NotNull Perception p) {
        BoxerSettings s = settings.get();
        if (!s.hunger().natural() || !p.inv().hasFood() || !p.hasTarget()) {
            return 0.0;
        }
        double hunger = p.self().hungerPct() * 20.0;
        if (hunger >= FULL_HUNGER) {
            return 0.0; // fully fed — release the latch
        }
        double eatThreshold = s.hunger().eatThreshold();
        if (hunger <= eatThreshold) {
            return PEAK_UTILITY; // hungry enough to break off and eat
        }
        // Between the eat threshold and full: a positive-but-quiet plateau that keeps
        // the exclusive latch alive while eating, without out-scoring ordinary combat.
        return LATCH_UTILITY;
    }

    /** Hard-seizes control for the whole meal: no other goal may pre-empt while eating. */
    @Override
    public boolean exclusive(@NotNull Perception p) {
        return utility(p) > 0.0;
    }

    /** A retreating, eating boxer does not swing. */
    @Override
    public boolean suppressesAttack() {
        return true;
    }

    @Override
    public @NotNull Intent decide(@NotNull Perception p, @NotNull BrainMemory mem) {
        BoxerSettings s = settings.get();
        int[] st = mem.ints("seekFood", 2);
        Perception.TargetState t = p.target();
        if (t == null) {
            st[0] = PHASE_BACK_OFF;
            st[1] = 0;
            return Intent.IDLE;
        }

        Perception.SelfState self = p.self();
        // Unit vector pointing FROM the target TO the boxer — the direction to retreat.
        Vec3d awayDir = new Vec3d(self.x() - t.x(), 0.0, self.z() - t.z()).horizontalNormalized();
        if (awayDir.lengthSqr() < 1.0E-8) {
            awayDir = new Vec3d(0.0, 0.0, 1.0); // degenerate overlap: pick a stable heading
        }
        Intent.FacingIntent faceMove = Intent.FacingIntent.faceMove();

        switch (st[0]) {
            case PHASE_BACK_OFF -> {
                // Sprint away; once there is room, advance to swapping in food next tick.
                if (t.distance() > BACKOFF_DISTANCE) {
                    st[0] = PHASE_SWAP_FOOD;
                }
                return new Intent(awayDir, faceMove, Intent.ActionIntent.none(), true,
                        Intent.JumpHint.NONE);
            }
            case PHASE_SWAP_FOOD -> {
                st[0] = PHASE_BEGIN_EAT;
                return new Intent(awayDir, faceMove,
                        Intent.ActionIntent.selectSlot(s.items().foodSlot()), true,
                        Intent.JumpHint.NONE);
            }
            case PHASE_BEGIN_EAT -> {
                // Stand to eat and start the use; hold for the eat duration.
                st[1] = EAT_TICKS;
                st[0] = PHASE_HOLD;
                return new Intent(Vec3d.ZERO, faceMove, Intent.ActionIntent.startUse(true), false,
                        Intent.JumpHint.NONE);
            }
            case PHASE_HOLD -> {
                st[1]--;
                if (st[1] <= 0) {
                    st[0] = PHASE_SWAP_BACK;
                    return new Intent(Vec3d.ZERO, faceMove, Intent.ActionIntent.releaseUse(), false,
                            Intent.JumpHint.NONE);
                }
                return new Intent(Vec3d.ZERO, faceMove, Intent.ActionIntent.none(), false,
                        Intent.JumpHint.NONE);
            }
            case PHASE_SWAP_BACK -> {
                // Swap the weapon back and reset; utility falls to ~0 once fed so the
                // latch releases and combat resumes.
                st[0] = PHASE_BACK_OFF;
                st[1] = 0;
                return new Intent(Vec3d.ZERO, faceMove,
                        Intent.ActionIntent.selectSlot(s.items().weaponSlot()), false,
                        Intent.JumpHint.NONE);
            }
            default -> {
                st[0] = PHASE_BACK_OFF;
                st[1] = 0;
                return Intent.IDLE;
            }
        }
    }
}

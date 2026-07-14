package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import org.jetbrains.annotations.NotNull;

/**
 * Blockhit combos (feature 6): while fighting in melee with a sword, the boxer
 * taps a sword-block in the gaps between its attacks. On a server running Mental
 * that right-click-with-a-sword gesture is read as a block, which re-arms the
 * sprint-knockback bonus between hits — the "blockhit" a real 1.8 PvPer does.
 *
 * <p>Unlike a movement routine this does not win arbitration; it LAYERS onto the
 * engage movement, injecting a brief {@code StartUse → ReleaseUse} tap into the
 * action stream on non-attacking ticks. It is a faithful gesture, not Mental's
 * exact wire blockhit (a clientless boxer has no connection ledger for that);
 * the approximation is documented and intentional.
 */
public final class BlockhitController {

    private static final String SCRATCH = "blockhit";
    /** Ticks between block taps — comfortably inside a sub-20-CPS click gap. */
    private static final int TAP_PERIOD = 6;

    /**
     * Maybe inject a block tap this tick. Call AFTER the click controller so
     * {@code attackFiringThisTick} is known; a tap is only raised on a tick with
     * no attack, so the block never fights a swing.
     *
     * @return true if a block/release action was emitted (the caller should treat
     *         the boxer as mid-use this tick).
     */
    public boolean apply(@NotNull Perception p, boolean blockHitEnabled, boolean inMelee,
            boolean attackFiringThisTick, @NotNull BrainMemory mem, @NotNull List<ActionIntent> out) {
        int[] state = mem.ints(SCRATCH, 2); // {phase, tickCounter}
        if (!blockHitEnabled || !p.inv().hasSword() || !inMelee) {
            state[0] = 0;
            state[1] = 0;
            return false;
        }
        // Phase 1: release the block raised on the previous tick.
        if (state[0] == 1) {
            out.add(ActionIntent.releaseUse());
            state[0] = 0;
            return true;
        }
        state[1]++;
        if (!attackFiringThisTick && state[1] % TAP_PERIOD == 0) {
            out.add(ActionIntent.startUse(true));
            state[0] = 1;
            return true;
        }
        return false;
    }
}

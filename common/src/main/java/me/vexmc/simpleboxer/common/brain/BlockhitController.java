package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * Blockhit combos (feature 6): while fighting in melee with a sword, the boxer
 * taps a sword-block in the gaps between its attacks. On a server running Mental
 * that right-click-with-a-sword gesture is read as a block, which re-arms the
 * sprint-knockback bonus between hits — the "blockhit" a real 1.8 PvPer does.
 *
 * <p>This class is only the CADENCE: it says which tick wants a tap. The tap
 * itself — the {@code StartUse} raise, the {@code ReleaseUse} that pairs with
 * it next tick, and the guarantee that the pair survives goal switches, melee
 * exits and sword breaks — is owned by {@link HandControl}, which consults this
 * oracle only inside the tap window it grants (routine idle, slot ledger on the
 * weapon, nothing held or owed). The cadence is a faithful gesture, not
 * Mental's exact wire blockhit (a clientless boxer has no connection ledger for
 * that); the approximation is documented and intentional.</p>
 */
public final class BlockhitController {

    private static final String SCRATCH = "blockhit";
    /** Ticks between block taps — comfortably inside a sub-20-CPS click gap. */
    private static final int TAP_PERIOD = 6;

    /**
     * Whether the blockhit rhythm wants a sword-block tap THIS tick. A tap is
     * only asked for on a tick with no attack, so the block never fights a
     * swing; the counter still advances on attack ticks (the gap-finder walks
     * the same clock a spam-clicking human's off hand does). The machine does
     * not consult the oracle on the tick after a granted tap (the release
     * tick), which freezes the counter for one tick and spaces granted taps
     * seven ticks apart.
     */
    public boolean desire(@NotNull Perception p, boolean blockHitEnabled, boolean inMelee,
            boolean attackFiringThisTick, @NotNull BrainMemory mem) {
        int[] state = mem.ints(SCRATCH, 1); // {tickCounter}
        if (!blockHitEnabled || !p.inv().hasSword() || !inMelee) {
            state[0] = 0;
            return false;
        }
        state[0]++;
        return !attackFiringThisTick && state[0] % TAP_PERIOD == 0;
    }
}

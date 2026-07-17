package me.vexmc.simpleboxer.common.brain;

import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent.UseKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single owner of the boxer's active hand. Goals, the click controller and
 * the blockhit cadence are REQUEST sources; only this machine appends hand
 * actions (slot selects, use starts, releases) to the tick's action list, so
 * pairing and ordering are decided in exactly one place instead of being an
 * emergent property of which goal happens to win which tick.
 *
 * <p>Three rules make the machine sound with no server feedback beyond the
 * live held slot:</p>
 *
 * <ul>
 *   <li><b>One FIFO.</b> Every action rides the boxer's single delayed action
 *       line, so emission order is wire order is server-processing order.
 *       Ordering invariants — a release pairs after its start, a swap never
 *       lands inside a hold, a tap lands after the swap that re-armed the
 *       weapon — are theorems of "one emitter + FIFO", not timing hopes.</li>
 *   <li><b>The ledger moves only with an emission.</b> {@code intendedSlot} is
 *       the slot the machine last ASKED the wire for, and it never changes
 *       without a {@code SelectSlot} emitted the same tick; any later hand
 *       action is therefore FIFO-ordered behind the swap that justified it.
 *       The live slot ({@code Perception.InventoryView#selectedSlot()}), which
 *       trails intent by the action delay, is consulted only to detect
 *       NON-delivery (a swap the server build could not construct, a respawn
 *       that replaced the inventory) and answered with a bounded re-ask —
 *       while a target holds the hand to a purpose. With nobody to fight, a
 *       lapsed disagreement is ADOPTED instead (the ledger's one silent
 *       move): an operator's swap is truth at rest, not a delivery
 *       failure.</li>
 *   <li><b>Holds have owners.</b> A hold-kind use belongs to the goal that
 *       raised it and stays up only while that goal keeps winning arbitration;
 *       any other owner of the tick forces the release FIRST — an interrupted
 *       eat is let go the moment the plan changed, exactly as a player's right
 *       button would be. A blockhit tap is a one-tick BLOCK hold whose release
 *       the machine owes unconditionally, across goal switches, melee exits,
 *       sword breaks and crit-spam activation.</li>
 * </ul>
 *
 * <p>Attack gating preserves 1.8 blockhit fidelity: sword-blocking is an item
 * use and neither the 1.8 client nor the server-side reimplementations (OCM
 * sword blocking, Mental's gesture ledger) gate {@code Player.attack} on the
 * attacker's use state — attacking through the raised block IS the technique.
 * So a raised BLOCK leaves clicks free, while an EAT hold, a non-weapon
 * ledger, or a use started this tick suppresses them — deterministically, at
 * any ping.</p>
 *
 * <p>Pure and owning-thread; all state lives in {@link BrainMemory#hand}, so a
 * respawn resets the hand with every other transient.</p>
 */
public final class HandControl {

    /**
     * Ticks before an intended slot the live slot still disagrees with is
     * re-asked — covers a laggy action line's RTT (10 decision ticks spans a
     * 1000 ms simulated ping) and survives a {@code SetCarriedItem} the server
     * build could not construct (the packet resolves null and never ships).
     */
    static final int SWAP_RETRY_TICKS = 10;

    /**
     * Hold budget: the longest a hold may stay raised without its owner
     * releasing it. The eat routine holds 34 ticks and a vanilla consume ends
     * at 32; anything past 40 is an abandoned hold (a goal bug, a plan that
     * died) that must not pin the hand forever. The clock advances only while
     * the brain routes — a paused brain freezes it with the hold up, so the
     * budget bounds post-resume damage, never the pause itself.
     */
    static final int MAX_HOLD_TICKS = 40;

    /**
     * What the machine permits for the rest of the tick after routing the
     * routine's request: whether the CPS clicks may attack/swing, and whether
     * the blockhit cadence may be consulted for a tap.
     */
    public record Gate(boolean clicksFree, boolean tapWindow) {}

    /**
     * Per-boxer machine state, owned by {@link BrainMemory} (plain public
     * fields, owning-thread only, like the rest of the scratchpad). Written by
     * {@link HandControl} alone at runtime; goals read the query methods, and
     * tests may arrange the fields directly.
     */
    public static final class HandState {

        /** The ledger: the hotbar slot the machine last asked the wire for.
         *  {@code -1} = unknown (fresh spawn / respawn reset) — adopted from
         *  the live perceived slot on the next route. */
        public int intendedSlot = -1;
        /** A hold-kind StartUse is on the wire, un-released. */
        public boolean holding;
        /** The kind of the live hold (meaningful while {@link #holding}). */
        public @Nullable UseKind holdKind;
        /** Goal id that raised the live hold; any other tick owner releases it. */
        public @Nullable String holdOwner;
        /** Ticks the live hold has been up — the budget clock. */
        public int heldTicks;
        /** A blockhit tap went out last tick; its release is owed. */
        public boolean releaseDue;
        /** Reconcile throttle: ticks until an undelivered swap may re-ask. */
        public int swapRetryIn;

        /**
         * Whether the hand is mid-use this tick (a live hold or a raised tap)
         * — the brain-clocked using signal, true for the WHOLE hold, where
         * core's decision-latched {@code usingItemTicks} (the use-item physics
         * source) models only a use's first four ticks.
         */
        public boolean using() {
            return holding || releaseDue;
        }

        /** Whether an EAT hold is live (the eat routine's "did my bite survive?"). */
        public boolean holdingEat() {
            return holding && holdKind == UseKind.EAT;
        }

        /** Respawn: the entity (and its inventory and use state) was replaced —
         *  nothing is in flight for the new life and nothing is owed. */
        public void reset() {
            intendedSlot = -1;
            holding = false;
            holdKind = null;
            holdOwner = null;
            heldTicks = 0;
            releaseDue = false;
            swapRetryIn = 0;
        }
    }

    /**
     * Route the winning goal's hand request and open the tick's gate. Emits,
     * in this order: corrective releases (an interrupted or over-budget hold,
     * a tap release displaced by a routine action), the routine's validated
     * request, then the weapon baseline / delivery reconcile (a target-less
     * lapsed disagreement is adopted, not re-asked). Call before the click
     * controller; pair with {@link #finish} after it.
     *
     * @param p               the matured per-tick snapshot
     * @param hand            the machine state from {@link BrainMemory#hand}
     * @param goalId          id of the goal that won arbitration this tick
     * @param request         the winner's intent action — a request, not an emission
     * @param routineOwnsHand true when the winner suppresses attacks: its
     *                        routine owns the hand (no clicks, taps, baseline)
     * @param weaponSlot      the configured weapon hotbar slot
     * @param out             sink for this tick's actions, in wire order
     */
    public @NotNull Gate route(@NotNull Perception p, @NotNull HandState hand,
            @NotNull String goalId, @NotNull ActionIntent request, boolean routineOwnsHand,
            int weaponSlot, @NotNull List<ActionIntent> out) {
        if (hand.intendedSlot < 0) {
            hand.intendedSlot = p.inv().selectedSlot();
        }
        if (hand.swapRetryIn > 0) {
            hand.swapRetryIn--;
        }

        // Hold upkeep: an owner change (another goal seized the tick mid-hold)
        // or a spent budget lets the button go NOW — before anything else this
        // tick touches the hand — so the release always precedes the
        // interrupting action on the wire.
        if (hand.holding) {
            hand.heldTicks++;
            if (!goalId.equals(hand.holdOwner) || hand.heldTicks > MAX_HOLD_TICKS) {
                release(hand, out);
            }
        }

        boolean startedMomentaryUse = false;
        boolean swappedThisTick = false;

        if (request instanceof ActionIntent.SelectSlot select) {
            // A swap never lands inside a hold or over an owed tap release: pay
            // the hand's debts first, in the same tick, earlier wire position.
            payDebts(hand, out);
            out.add(select);
            hand.intendedSlot = select.slot();
            hand.swapRetryIn = SWAP_RETRY_TICKS;
            swappedThisTick = true;
        } else if (request instanceof ActionIntent.StartUse use) {
            if (use.slot() >= 0 && use.slot() != hand.intendedSlot) {
                // The hand is not (and was not asked to be) on the item this
                // use was written for — firing anyway would use the WRONG item
                // (a resumed rod cast right-clicking the sword; a resumed heal
                // throwing whatever is held). Drop it: the requester's own
                // confirm/retry loop re-swaps and re-uses.
            } else if (hand.holding && use.kind().holds() && goalId.equals(hand.holdOwner)) {
                // A live same-owner hold re-requested: a refresh, not a new use.
            } else {
                payDebts(hand, out);
                out.add(use);
                if (use.kind().holds()) {
                    hand.holding = true;
                    hand.holdKind = use.kind();
                    hand.holdOwner = goalId;
                    hand.heldTicks = 0;
                } else {
                    startedMomentaryUse = true; // throw/cast: nothing owed
                }
            }
        } else if (request instanceof ActionIntent.ReleaseUse) {
            // Only a live hold may be released — an unpaired release (a goal
            // FSM resuming after its hold was already let go) never ships.
            if (hand.holding) {
                release(hand, out);
            }
        } else if (!routineOwnsHand && p.hasTarget()
                && !hand.holding && !hand.releaseDue
                && hand.intendedSlot != weaponSlot) {
            // Baseline: a fighting boxer's resting hand is the weapon. The
            // LEDGER is consulted, never the delay-stale live slot, so an exit
            // swap already in flight is not duplicated — and because the
            // ledger only ever moves together with an emission, any tap or
            // click that follows is FIFO-ordered behind this correction.
            out.add(ActionIntent.selectSlot(weaponSlot));
            hand.intendedSlot = weaponSlot;
            hand.swapRetryIn = SWAP_RETRY_TICKS;
            swappedThisTick = true;
        }

        // Delivery reconcile: the FIFO orders, it does not guarantee arrival —
        // a swap can resolve to no packet on an exotic build and a respawn
        // replaces the inventory wholesale. A boxer with a TARGET defends its
        // ledger: when the live slot still disagrees a full retry window after
        // the last ask, ask again (idempotent; never while a hold or an owed
        // release pins the hand). At rest the same disagreement means the
        // operator (or the server) moved the hand — adopt the live slot, the
        // ledger's one silent move: nothing goes on the wire, so nothing can
        // be disordered, and the baseline re-arms the weapon when a fight
        // next starts.
        if (!swappedThisTick && !hand.holding && !hand.releaseDue
                && hand.swapRetryIn == 0 && p.inv().selectedSlot() != hand.intendedSlot) {
            if (p.hasTarget()) {
                out.add(ActionIntent.selectSlot(hand.intendedSlot));
                hand.swapRetryIn = SWAP_RETRY_TICKS;
            } else {
                hand.intendedSlot = p.inv().selectedSlot();
            }
        }

        boolean clicksFree = !routineOwnsHand
                && hand.intendedSlot == weaponSlot
                && !hand.holding
                && !startedMomentaryUse;
        boolean tapWindow = !routineOwnsHand
                && request instanceof ActionIntent.None
                && hand.intendedSlot == weaponSlot
                && !hand.holding
                && !hand.releaseDue;
        return new Gate(clicksFree, tapWindow);
    }

    /**
     * Close the tick after the click controller has spoken: pay the tap
     * release owed from last tick — late in the tick, after any attack, the
     * wire order a 1.8 blockhitter's attack-through-block produces — or raise
     * a fresh tap the cadence asked for inside the granted window.
     */
    public void finish(@NotNull HandState hand, boolean tapDesired,
            @NotNull List<ActionIntent> out) {
        if (hand.releaseDue) {
            out.add(ActionIntent.releaseUse());
            hand.releaseDue = false;
            return;
        }
        if (tapDesired) {
            out.add(ActionIntent.blockTap());
            hand.releaseDue = true;
        }
    }

    /** Let go of anything the hand still owes before a displacing action. */
    private static void payDebts(@NotNull HandState hand, @NotNull List<ActionIntent> out) {
        if (hand.releaseDue) {
            out.add(ActionIntent.releaseUse());
            hand.releaseDue = false;
        }
        if (hand.holding) {
            release(hand, out);
        }
    }

    private static void release(@NotNull HandState hand, @NotNull List<ActionIntent> out) {
        out.add(ActionIntent.releaseUse());
        hand.holding = false;
        hand.holdKind = null;
        hand.holdOwner = null;
        hand.heldTicks = 0;
    }
}

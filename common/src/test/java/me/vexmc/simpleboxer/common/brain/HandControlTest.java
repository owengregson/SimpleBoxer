package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandControlTest {

    /* The default Items layout (BoxerSettings.DEFAULTS.items()): weapon 0,
     * rod 1, pot 2, food 3. */
    private static final int WEAPON = 0;
    private static final int ROD = 1;
    private static final int POT = 2;
    private static final int FOOD = 3;

    private final HandControl hand = new HandControl();

    /** A melee perception whose LIVE (server-truth) selected slot is given —
     *  the machine's ledger is deliberately allowed to disagree with it. */
    private static Perception melee(int liveSlot) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false, 1.0, 1.0,
                Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState t = new Perception.TargetState(
                2, 64, 0, 65.62, Vec3d.ZERO, 0, 0, 0, 2.0, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, true, true, false, liveSlot);
        return new Perception(self, t, Perception.TerrainView.OPEN, inv,
                Perception.CombatState.IDLE, 0);
    }

    /** Same, with nobody to fight (the baseline must stay quiet). */
    private static Perception noTarget(int liveSlot) {
        Perception withTarget = melee(liveSlot);
        return new Perception(withTarget.self(), null, Perception.TerrainView.OPEN,
                withTarget.inv(), Perception.CombatState.IDLE, 0);
    }

    /** One machine tick: route the request, then close with an (optionally
     *  granted) tap — the same call order Brain.tick uses. */
    private List<ActionIntent> tick(BrainMemory mem, Perception p, String goalId,
            ActionIntent request, boolean routineOwnsHand, boolean tapDesired) {
        List<ActionIntent> out = new ArrayList<>();
        HandControl.Gate gate = hand.route(p, mem.hand, goalId, request,
                routineOwnsHand, WEAPON, out);
        hand.finish(mem.hand, gate.tapWindow() && tapDesired, out);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Happy-path wire regression (byte-identical to pre-machine)         */
    /* ------------------------------------------------------------------ */

    @Test
    void potHealHappyPathWireIsUnchanged() {
        BrainMemory mem = new BrainMemory(1L);
        // Phase 0 retreat: movement only.
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.none(), true, false));
        // Phase 1: swap to the pot slot.
        assertEquals(List.of(ActionIntent.selectSlot(POT)),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.selectSlot(POT), true, false));
        // Phase 2: the throw — the ledger is on the pot slot; the live slot has
        // caught up (the swap dispatched last tick at zero ping).
        assertEquals(List.of(ActionIntent.throwUse(POT)),
                tick(mem, melee(POT), "potHeal", ActionIntent.throwUse(POT), true, false));
        // Phase 3: the ten-tick confirm window — silent.
        for (int t = 1; t <= 10; t++) {
            assertEquals(List.of(),
                    tick(mem, melee(POT), "potHeal", ActionIntent.none(), true, false),
                    "wait tick " + t);
        }
        // Phase 4: the weapon comes back.
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(POT), "potHeal", ActionIntent.selectSlot(WEAPON), true, false));
    }

    @Test
    void seekFoodHappyPathWireIsUnchanged() {
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(ActionIntent.selectSlot(FOOD)),
                tick(mem, melee(WEAPON), "seekFood", ActionIntent.selectSlot(FOOD), true, false));
        assertEquals(List.of(ActionIntent.eat(FOOD)),
                tick(mem, melee(FOOD), "seekFood", ActionIntent.eat(FOOD), true, false));
        // SeekFood's hold: EAT_TICKS = 34, so 33 silent hold ticks precede the
        // release tick. heldTicks reaches 33 here — under the 40 budget, so the
        // machine never lets go on its own (the release below is the OWNER's).
        for (int t = 1; t <= 33; t++) {
            assertEquals(List.of(),
                    tick(mem, melee(FOOD), "seekFood", ActionIntent.none(), true, false),
                    "hold tick " + t);
            assertTrue(mem.hand.using(), "using through the whole eat, tick " + t);
        }
        assertEquals(List.of(ActionIntent.releaseUse()),
                tick(mem, melee(FOOD), "seekFood", ActionIntent.releaseUse(), true, false));
        assertFalse(mem.hand.using());
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(FOOD), "seekFood", ActionIntent.selectSlot(WEAPON), true, false));
    }

    @Test
    void rodPokeHappyPathWireIsUnchanged() {
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(ActionIntent.selectSlot(ROD)),
                tick(mem, melee(WEAPON), "rodPoke", ActionIntent.selectSlot(ROD), true, false));
        assertEquals(List.of(ActionIntent.cast(ROD)),
                tick(mem, melee(ROD), "rodPoke", ActionIntent.cast(ROD), true, false));
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(ROD), "rodPoke", ActionIntent.selectSlot(WEAPON), true, false));
    }

    @Test
    void blockhitPeriodSevenCadenceAndAttackThroughBlockOrder() {
        // The cadence pin: the counter advances on every eligible tick, a tap
        // fires at counter % 6 == 0, and the release tick freezes the counter
        // (the machine does not consult the cadence while a release is owed).
        // Hand-computed: ticks 1-5 count 1-5 (silent); tick 6 counts 6 → TAP;
        // tick 7 pays the release (counter frozen at 6); ticks 8-12 count
        // 7-11; tick 13 counts 12 → TAP. Period 7 after the first tap. The
        // tick-7 ATTACK asserts the machine's gate (declared delta D3, §9): a
        // raised BLOCK leaves clicks free, so a due click lands as [attack,
        // swing, release] — the 1.8 attack-through-block order that the
        // decision-tick use latch otherwise eats.
        BlockhitController cadence = new BlockhitController();
        BrainMemory mem = new BrainMemory(1L);
        for (int t = 1; t <= 13; t++) {
            List<ActionIntent> out = new ArrayList<>();
            Perception p = melee(WEAPON);
            HandControl.Gate gate = hand.route(p, mem.hand, "engage",
                    ActionIntent.none(), false, WEAPON, out);
            // A CPS click lands exactly on the release tick (tick 7): the wire
            // order must be attack, swing, THEN the release — the 1.8
            // attack-through-block a real blockhitter produces.
            boolean attackFiring = t == 7;
            if (attackFiring) {
                assertTrue(gate.clicksFree(), "clicks stay free over a raised block");
                out.add(ActionIntent.attack());
                out.add(ActionIntent.swing());
            }
            boolean tap = gate.tapWindow()
                    && cadence.desire(p, true, true, attackFiring, mem);
            hand.finish(mem.hand, tap, out);

            switch (t) {
                case 6, 13 -> assertEquals(List.of(ActionIntent.blockTap()), out, "tick " + t);
                case 7 -> assertEquals(List.of(ActionIntent.attack(), ActionIntent.swing(),
                        ActionIntent.releaseUse()), out, "tick " + t);
                default -> assertEquals(List.of(), out, "tick " + t);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Race pins — each schedule was legal (and wrong) pre-machine        */
    /* ------------------------------------------------------------------ */

    @Test
    void strandedTapReleaseIsPaidAcrossAGoalSwitch() {
        // R1/R1b: pre-machine, the release lived in blockhit phase scratch and
        // its emitting code path was skipped the moment a suppressing goal (or
        // a melee exit) owned the tick — the block stayed raised on the wire.
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(ActionIntent.blockTap()),
                tick(mem, melee(WEAPON), "engage", ActionIntent.none(), false, true));
        // The heal reflex seizes the very next tick (retreat = movement only):
        // the machine pays the owed release regardless of who owns the tick.
        assertEquals(List.of(ActionIntent.releaseUse()),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.none(), true, false));
        // Nothing further is owed.
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.none(), true, false));
    }

    @Test
    void inFlightPotSwapCanNeverBeBlockhitIntoAThrow() {
        // R2 (the 56de9de residue): swap-to-pot in flight, target lost, target
        // re-acquired while the LIVE slot still reads the weapon. Pre-machine
        // the live-slot guard passed and the tap's use-item landed AFTER the
        // pot swap on the FIFO — a thrown heal. The ledger closes it.
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(ActionIntent.selectSlot(POT)),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.selectSlot(POT), true, false));
        // Target gone: nothing owns the hand, and no baseline without a fight.
        assertEquals(List.of(),
                tick(mem, noTarget(WEAPON), "idle", ActionIntent.none(), false, false));
        // Target re-acquired, swap STILL in flight (live slot = weapon). The
        // baseline re-arms the weapon FIRST; the granted tap is FIFO-ordered
        // behind it, so it can only ever land with the weapon in hand.
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON), ActionIntent.blockTap()),
                tick(mem, melee(WEAPON), "engage", ActionIntent.none(), false, true));
    }

    @Test
    void preemptedEatIsReleasedTheTickTheOwnerChanges() {
        // R3 first half: pre-machine, NOBODY released a preempted eat — the
        // heal retreated for up to 40 ticks with the bite still active.
        BrainMemory mem = new BrainMemory(1L);
        tick(mem, melee(WEAPON), "seekFood", ActionIntent.selectSlot(FOOD), true, false);
        tick(mem, melee(FOOD), "seekFood", ActionIntent.eat(FOOD), true, false);
        assertTrue(mem.hand.holdingEat());
        // The heal seizes with a movement-only retreat tick: owner changed →
        // the button is let go immediately.
        assertEquals(List.of(ActionIntent.releaseUse()),
                tick(mem, melee(FOOD), "potHeal", ActionIntent.none(), true, false));
        assertFalse(mem.hand.holdingEat());
        // Its later pot swap finds the hand already free — no wedged swap.
        assertEquals(List.of(ActionIntent.selectSlot(POT)),
                tick(mem, melee(FOOD), "potHeal", ActionIntent.selectSlot(POT), true, false));
    }

    @Test
    void interruptingSwapOnTheSeizureTickReleasesFirstInTheSameTick() {
        // I2 wire-position pin: release BEFORE the displacing swap, one tick.
        BrainMemory mem = new BrainMemory(1L);
        tick(mem, melee(WEAPON), "seekFood", ActionIntent.selectSlot(FOOD), true, false);
        tick(mem, melee(FOOD), "seekFood", ActionIntent.eat(FOOD), true, false);
        assertEquals(List.of(ActionIntent.releaseUse(), ActionIntent.selectSlot(POT)),
                tick(mem, melee(FOOD), "potHeal", ActionIntent.selectSlot(POT), true, false));
    }

    @Test
    void resumedUseOnTheWrongSlotIsDropped() {
        // R4: a goal FSM resumed out of position (its swap phase ran a life or
        // an episode ago) must not fire the wrong item. The ledger is on the
        // weapon (fresh adopt from the live slot); both declared uses drop.
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "rodPoke", ActionIntent.cast(ROD), true, false));
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "potHeal", ActionIntent.throwUse(POT), true, false));
    }

    @Test
    void exitSwapIsNotDuplicatedByTheBaseline() {
        // R6: pre-machine, Engage's reselect read the delay-stale LIVE slot and
        // re-sent the weapon swap the heal exit had already put in flight.
        BrainMemory mem = new BrainMemory(1L);
        // Adopt the pot slot as current, then the heal's exit swap.
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(POT), "potHeal", ActionIntent.selectSlot(WEAPON), true, false));
        // Engage's first tick: live slot STILL pot (in flight) — ledger already
        // says weapon → silence (retry window 10 also not yet elapsed).
        assertEquals(List.of(),
                tick(mem, melee(POT), "engage", ActionIntent.none(), false, false));
    }

    @Test
    void undeliveredSwapIsReAskedAfterTheRetryWindow() {
        // R7: the swap resolved to no packet (or the RTT exceeds the window).
        // Arithmetic: the ask arms swapRetryIn = 10; route decrements at each
        // tick top — 9 after tick 1, …, 1 after tick 9 — so tick 10 reaches 0
        // with the live slot still wrong and re-asks.
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(POT), "engage", ActionIntent.none(), false, false),
                "baseline asks (ledger adopted the pot slot, weapon wanted)");
        for (int t = 1; t <= 9; t++) {
            assertEquals(List.of(),
                    tick(mem, melee(POT), "engage", ActionIntent.none(), false, false),
                    "in-flight tick " + t);
        }
        assertEquals(List.of(ActionIntent.selectSlot(WEAPON)),
                tick(mem, melee(POT), "engage", ActionIntent.none(), false, false),
                "tick 10 re-asks");
    }

    @Test
    void targetlessBoxerAdoptsAnOperatorSwapInsteadOfFightingIt() {
        // D4/R7 scope: the operator (or the server) moved the held slot while
        // nobody was targeted, and the retry window has long expired. The
        // machine must not re-ask its stale ledger slot forever — operator
        // truth wins at rest: the live slot is adopted silently (no emission),
        // and the baseline re-arms the weapon only once a fight starts.
        BrainMemory mem = new BrainMemory(1L);
        mem.hand.intendedSlot = WEAPON; // a settled ledger from an earlier fight
        assertEquals(List.of(),
                tick(mem, noTarget(ROD), "idle", ActionIntent.none(), false, false));
        assertEquals(ROD, mem.hand.intendedSlot);
    }

    @Test
    void abandonedHoldIsReleasedByTheBudget() {
        // R8/R9 safety net. Arithmetic: the eat is raised with heldTicks = 0;
        // upkeep increments once per subsequent tick, so tick 40 reads 40
        // (still within budget: the guard is heldTicks > 40) and tick 41 reads
        // 41 → the machine lets go on its own.
        BrainMemory mem = new BrainMemory(1L);
        tick(mem, melee(WEAPON), "seekFood", ActionIntent.selectSlot(FOOD), true, false);
        tick(mem, melee(FOOD), "seekFood", ActionIntent.eat(FOOD), true, false);
        for (int t = 1; t <= 40; t++) {
            assertEquals(List.of(),
                    tick(mem, melee(FOOD), "seekFood", ActionIntent.none(), true, false),
                    "budget tick " + t);
        }
        assertEquals(List.of(ActionIntent.releaseUse()),
                tick(mem, melee(FOOD), "seekFood", ActionIntent.none(), true, false));
    }

    @Test
    void respawnResetOwesNothingAndAdoptsTheNewLife() {
        // K1: die mid-hold; BrainMemory.onRespawn() resets the hand. The new
        // life must produce no stray release and no needless swap.
        BrainMemory mem = new BrainMemory(1L);
        tick(mem, melee(WEAPON), "seekFood", ActionIntent.selectSlot(FOOD), true, false);
        tick(mem, melee(FOOD), "seekFood", ActionIntent.eat(FOOD), true, false);
        mem.onRespawn();
        assertFalse(mem.hand.using());
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "engage", ActionIntent.none(), false, false));
    }

    @Test
    void unpairedReleaseRequestsNeverShip() {
        // I1: a goal FSM resuming after its hold was already let go re-emits
        // its release; the wire must not see it.
        BrainMemory mem = new BrainMemory(1L);
        assertEquals(List.of(),
                tick(mem, melee(WEAPON), "seekFood", ActionIntent.releaseUse(), true, false));
    }

    @Test
    void usingSignalCoversTapsExactly() {
        // The tap's raised tick and only that: using() true after the raise,
        // false once the release is paid — the whole-hold using signal. The
        // landed use-item slowdown keeps reading core's 4-tick latch; the §9
        // coexistence contract names this signal as its follow-up source.
        BrainMemory mem = new BrainMemory(1L);
        tick(mem, melee(WEAPON), "engage", ActionIntent.none(), false, true);
        assertTrue(mem.hand.using());
        tick(mem, melee(WEAPON), "engage", ActionIntent.none(), false, false);
        assertFalse(mem.hand.using());
    }

    @Test
    void clickGateFollowsTheLedgerAndTheHoldKind() {
        BrainMemory mem = new BrainMemory(1L);
        List<ActionIntent> out = new ArrayList<>();
        // Ledger moves to the rod: clicks close (even before the swap lands).
        HandControl.Gate offWeapon = hand.route(melee(WEAPON), mem.hand, "rodPoke",
                ActionIntent.selectSlot(ROD), true, WEAPON, out);
        assertFalse(offWeapon.clicksFree());
        assertFalse(offWeapon.tapWindow());
        // Back on the weapon with nothing held: clicks and taps open.
        out.clear();
        HandControl.Gate idle = hand.route(melee(ROD), mem.hand, "engage",
                ActionIntent.selectSlot(WEAPON), false, WEAPON, out);
        assertTrue(idle.clicksFree());
        assertFalse(idle.tapWindow(), "a routine swap occupied this tick");
    }
}

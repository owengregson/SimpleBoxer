package me.vexmc.simpleboxer.common.brain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Behaviour checks for {@link PotHealGoal}: the utility trigger + hysteresis
 * latch, its exclusivity, the retreat -> swap -> throw-on-the-run -> wait ->
 * repeat/swap-back finite state machine, launch-confirmation counting (only a
 * server-observed ThrownPotion charges the budget), and the varied — never
 * metronomic — heal weave.
 */
class PotHealGoalTest {

    // Items.DEFAULT slot layout.
    private static final int POT_SLOT = 2;
    private static final int WEAPON_SLOT = 0;

    // The goal's mem.ints("potHeal", 7) layout.
    private static final int PHASE = 0;
    private static final int POTS_THROWN = 1;
    private static final int GAVE_UP = 3;

    /** A self-heal profile: arm at 6 HP, recover at 18 HP, spend at most {@code cap} pots. */
    private static BoxerSettings settings(boolean enabled, boolean invincible, int cap) {
        return BoxerSettings.DEFAULTS
                .withInvincible(invincible)
                .withSelfHeal(new BoxerSettings.SelfHeal(enabled, 6.0, 18.0, cap));
    }

    /**
     * Perception with a live target: health as a [0,1] fraction, pot availability,
     * range, and the server's cumulative confirmed-launch count.
     */
    private static Perception perc(double healthPct, boolean hasPots, double distance,
            int launched) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                healthPct, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.TargetState target = new Perception.TargetState(
                distance, 64, 0, 65.6, Vec3d.ZERO,
                90.0, 0.0, 0.0, distance, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, hasPots, true, true, 0);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                inv, new Perception.CombatState(1.0, false, 0L, launched), 0);
    }

    /**
     * {@code decide()} plus the hand machine's ledger move: in production,
     * {@code HandControl.route} sets {@code intendedSlot} the same tick for any
     * {@code SelectSlot} the routine requests ("the ledger moves only with an
     * emission"), and the goal's phase-2 ledger check reads it back. Without
     * mirroring that move here the FSM walk would re-press the pot key forever.
     */
    private static Intent decideRouted(PotHealGoal goal, Perception p, BrainMemory mem) {
        Intent intent = goal.decide(p, mem);
        if (intent.action() instanceof Intent.ActionIntent.SelectSlot sel) {
            mem.hand.intendedSlot = sel.slot();
        }
        return intent;
    }

    private static Perception noTarget(double healthPct) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                healthPct, 1.0, Perception.UseItemState.NONE, false, 0.1, -1,
                20.0, 0, 3.0, 1.0, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, true, true, true, 0);
        return new Perception(self, null, Perception.TerrainView.OPEN,
                inv, Perception.CombatState.IDLE, 0);
    }

    // ---- (a) utility is zero when the routine cannot / should not fire --------

    @Test
    void utilityZeroWhenIneligible() {
        // Invincible: never heals even at 1 HP — the routine is mortal-only.
        PotHealGoal invincible = new PotHealGoal(() -> settings(true, true, 6));
        assertEquals(0.0, invincible.utility(perc(0.05, true, 3.0, 0)));
        assertFalse(invincible.exclusive(perc(0.05, true, 3.0, 0)));

        // Self-heal disabled.
        PotHealGoal disabled = new PotHealGoal(() -> settings(false, false, 6));
        assertEquals(0.0, disabled.utility(perc(0.05, true, 3.0, 0)));

        // No pots in the kit.
        PotHealGoal noPots = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, noPots.utility(perc(0.05, false, 3.0, 0)));

        // No target to disengage from.
        PotHealGoal solo = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, solo.utility(noTarget(0.05)));

        // Eligible but healthy (full HP is well above trigger).
        PotHealGoal healthy = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, healthy.utility(perc(1.0, true, 3.0, 0)));
    }

    // ---- (b) high utility + exclusive when mortal & low; hysteresis holds -----

    @Test
    void utilityHighAndExclusiveWhenMortalAndLow() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));

        // 2 HP (0.1 * 20) is well under the 6 HP trigger: strong, exclusive.
        double u = goal.utility(perc(0.1, true, 3.0, 0));
        assertTrue(u > 0.5, "low-health utility should out-score ordinary engage, was " + u);
        assertTrue(u <= PotHealGoal.MAX_UTILITY);
        assertTrue(goal.exclusive(perc(0.1, true, 3.0, 0)),
                "a healing boxer must hard-seize control");
    }

    @Test
    void hysteresisKeepsHealingUntilResumeHealth() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));

        // Arm the latch at low health.
        assertTrue(goal.utility(perc(0.1, true, 3.0, 0)) > 0.5);

        // 12 HP: above the 6 HP trigger but below the 18 HP resume line. Because
        // the latch is armed, it must STILL be healing (utility > 0, exclusive).
        double mid = goal.utility(perc(0.6, true, 3.0, 0));
        assertTrue(mid > 0.0, "latch must hold between trigger and resume, was " + mid);
        assertTrue(goal.exclusive(perc(0.6, true, 3.0, 0)));

        // 18 HP reaches the resume line: the latch releases, utility falls to 0.
        assertEquals(0.0, goal.utility(perc(0.9, true, 3.0, 0)));
        assertFalse(goal.exclusive(perc(0.9, true, 3.0, 0)));
    }

    // ---- (c) the retreat -> swap -> throw -> wait -> repeat/swap-back FSM ------

    @Test
    void fsmRetreatsSwapsThrowsConfirmsAndReleasesOnRecovery() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(1L);

        // Phase 0, first tick: too close (< 3.5) — keep fleeing, but the wind-up
        // is already front-loaded: the pot swap ships NOW and the facing is the
        // throw aim (its yaw IS the flee line), so pitch settles during the
        // retreat and no later tick is spent swapping or aiming.
        Intent flee = decideRouted(goal, perc(0.1, true, 2.0, 0), mem);
        assertEquals(new Vec3d(-1, 0, 0), flee.moveDirWorld(),
                "flee heading points away from the target");
        assertTrue(flee.wantSprint(), "sprint away while creating space");
        assertInstanceOf(Intent.FacingIntent.AimAt.class, flee.facing(),
                "aiming the throw already, on the flee yaw");
        Intent.ActionIntent.SelectSlot early =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, flee.action());
        assertEquals(POT_SLOT, early.slot(), "the pot swap front-loads into the retreat");
        assertEquals(0, mem.ints("potHeal", 7)[PHASE], "still too close, stay in phase 0");

        // Phase 0 -> 2 the tick the throw gate (3.5) opens: no separate swap tick.
        Intent lastFlee = decideRouted(goal, perc(0.1, true, 5.0, 0), mem);
        assertInstanceOf(Intent.ActionIntent.None.class, lastFlee.action(),
                "the swap shipped on tick one; the gate tick carries no action");
        assertEquals(2, mem.ints("potHeal", 7)[PHASE]);

        // Phase 2: throw ON THE RUN. The aim point sits 0.3 blocks ahead along
        // the flee heading at FOOT height: awayDir = normalize(self - target) =
        // normalize((0-5, 0, 0-0)) = (-1, 0, 0), so the point is
        // (0 + (-1)*0.3, 64.0, 0 + 0*0.3) = (-0.3, 64.0, 0.0).
        Intent throw1 = decideRouted(goal, perc(0.1, true, 5.0, 0), mem);
        assertInstanceOf(Intent.ActionIntent.StartUse.class, throw1.action());
        assertTrue(throw1.wantSprint(), "throws at a sprint, not standing");
        assertTrue(throw1.moveDirWorld().lengthSqr() > 0.99, "juking on a unit heading");
        Intent.FacingIntent.AimAt aim = (Intent.FacingIntent.AimAt) throw1.facing();
        assertEquals(-0.3, aim.x(), 1.0E-12);
        assertEquals(64.0, aim.y(), 1.0E-12, "foot height — the yaw-defining offset is horizontal");
        assertEquals(0.0, aim.z(), 1.0E-12);
        // The desired pitch Brain.applyFacing derives from this point:
        // eyeDy = 64.0 - (64.0 + 1.62) = -1.62, dist = 0.3, and
        // pitch = -toDegrees(atan2(-1.62, 0.3)) = toDegrees(atan(5.4)) = 79.5083.
        double dist = Math.hypot(aim.x(), aim.z());
        double pitch = -Math.toDegrees(Math.atan2(aim.y() - (64.0 + 1.62), Math.max(dist, 1.0E-4)));
        assertEquals(79.51, pitch, 0.05, "throw pitch pinned inside the 75-85 window");
        assertTrue(pitch > 75.0 && pitch < 85.0, "the spec'd splash window");
        assertEquals(0, mem.ints("potHeal", 7)[POTS_THROWN],
                "an unconfirmed use-item charges nothing at throw time");
        assertEquals(3, mem.ints("potHeal", 7)[PHASE]);

        // Phase 3 with the launch CONFIRMED (the server spawned our ThrownPotion:
        // launched 0 -> 1): the window settles at the rethrow floor (4 weave
        // ticks), not the full 10-tick failure timeout — the pot-spam cadence.
        for (int i = 0; i < 3; i++) {
            Intent wait = decideRouted(goal, perc(0.1, true, 5.0, 1), mem);
            assertTrue(wait.moveDirWorld().lengthSqr() > 0.99, "weaves through the splash");
            assertTrue(wait.wantSprint(), "keeps sprinting through the wait");
            assertInstanceOf(Intent.FacingIntent.AimAt.class, wait.facing());
            assertEquals(3, mem.ints("potHeal", 7)[PHASE], "still inside the rethrow floor");
            assertEquals(0, mem.ints("potHeal", 7)[POTS_THROWN], "counts only at settle");
        }
        decideRouted(goal, perc(0.1, true, 5.0, 1), mem); // 4th weave tick: floor reached, settles
        assertEquals(1, mem.ints("potHeal", 7)[POTS_THROWN], "one CONFIRMED throw");
        assertEquals(2, mem.ints("potHeal", 7)[PHASE],
                "still low -> straight back to the throw, the pot is in hand");

        // Second throw immediately (phase 2); its baseline is the current count (1).
        Intent throw2 = decideRouted(goal, perc(0.1, true, 5.0, 1), mem);
        assertInstanceOf(Intent.ActionIntent.StartUse.class, throw2.action());

        // The second pot lands (1 -> 2) and health recovers DURING the weave: the
        // settle both confirms the throw and routes to phase 4 (swap back).
        for (int i = 0; i < 4; i++) {
            decideRouted(goal, perc(0.95, true, 5.0, 2), mem); // 19 HP >= 18 resume
        }
        assertEquals(2, mem.ints("potHeal", 7)[POTS_THROWN], "second throw confirmed");
        assertEquals(4, mem.ints("potHeal", 7)[PHASE], "recovered -> done phase");

        // Phase 4: swap back to the weapon, reset counters, drop the latch.
        Intent back = decideRouted(goal, perc(0.95, true, 5.0, 2), mem);
        assertInstanceOf(Intent.ActionIntent.SelectSlot.class, back.action());
        assertEquals(WEAPON_SLOT, ((Intent.ActionIntent.SelectSlot) back.action()).slot());
        assertEquals(0, mem.ints("potHeal", 7)[PHASE], "phase reset");
        assertEquals(0, mem.ints("potHeal", 7)[POTS_THROWN], "potsThrown reset");

        // Latch released: fully recovered, utility back to 0.
        assertEquals(0.0, goal.utility(perc(0.95, true, 5.0, 2)));
    }

    /**
     * The feedback loop: a use-item the server silently swallows (spam gate,
     * empty hand) never spawns a ThrownPotion, so the budget is NOT charged and
     * the throw is retried — but only THROW_FAIL_CAP (3) times, after which the
     * episode ends through the durable give-up path instead of looping forever.
     * An unconfirmed window never settles early, so each attempt runs the FULL
     * failure timeout. Tick arithmetic: 1 (phase 0; 5.0 > 3.5 advances at once,
     * swap shipping on that same tick) + 3 cycles of [1 throw + 10 waits] +
     * 1 swap-back = 35 decides.
     */
    @Test
    void unconfirmedThrowsRetryBoundedThenGiveUpDurably() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(2L);
        Perception low = perc(0.1, true, 5.0, 0); // the count NEVER advances

        assertTrue(goal.utility(low) > 0.0, "latch arms before the first decide");

        int startUses = 0;
        int weaponSwaps = 0;
        for (int tick = 0; tick < 60 && weaponSwaps == 0; tick++) {
            Intent intent = decideRouted(goal, low, mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                startUses++;
            } else if (intent.action() instanceof Intent.ActionIntent.SelectSlot sel
                    && sel.slot() == WEAPON_SLOT) {
                weaponSwaps++;
            }
        }

        assertEquals(3, startUses, "exactly THROW_FAIL_CAP unconfirmed attempts");
        assertEquals(1, weaponSwaps, "then swaps back to the weapon");
        assertEquals(0, mem.ints("potHeal", 7)[POTS_THROWN],
                "no phantom pot ever charged the budget");
        assertEquals(1, mem.ints("potHeal", 7)[GAVE_UP], "durable give-up");
        assertEquals(0.0, goal.utility(low), "utility stays silent while still low");
    }

    /**
     * The interrupted-episode regression (the pot-budget integration failure):
     * recovery is OBSERVED by {@code utility} — which returns 0 with the very
     * perception {@code decide} would have seen, so decide can never run the
     * FSM's own cleanup on this path. The latch release must ABORT the episode
     * to phase 0; parking mid-phase-3 instead left a stale confirm window that
     * the next round resumed: it phantom-settled against the PREVIOUS round's
     * launch and rethrew with the weapon in hand (re-armed by the engage-gap
     * baseline) — a use the hand machine silently drops, thrice, then a
     * durable give-up with health still on the floor.
     */
    @Test
    void recoveryReleaseAbortsTheEpisodeInsteadOfParkingIt() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(7L);

        // Round 1: arbitration arms the latch (utility always scores before
        // decide), then: open (swap front-loads), throw pot 1, launch confirmed
        // (0 -> 1) while still inside the rethrow floor.
        assertTrue(goal.utility(perc(0.1, true, 5.0, 0)) > 0.5, "the latch arms");
        decideRouted(goal, perc(0.1, true, 5.0, 0), mem); // phase 0 -> 2, swap shipped
        decideRouted(goal, perc(0.1, true, 5.0, 0), mem); // phase 2: throw, base = 0
        decideRouted(goal, perc(0.1, true, 5.0, 1), mem); // phase 3, elapsed 1: confirmed
        assertEquals(3, mem.ints("potHeal", 7)[PHASE], "mid-window, inside the floor");

        // The splash lands, health crosses resume: the release aborts the episode.
        assertEquals(0.0, goal.utility(perc(0.95, true, 5.0, 1)));
        assertEquals(0, mem.ints("potHeal", 7)[PHASE],
                "the latch release aborts the parked episode to phase 0");

        // The engage gap re-arms the weapon (HandControl's baseline would).
        mem.hand.intendedSlot = WEAPON_SLOT;

        // Round 2: health re-drops. A FRESH episode must start — the retreat
        // front-loads the pot swap again, re-establishing the hand invariant.
        assertTrue(goal.utility(perc(0.1, true, 5.0, 1)) > 0.5, "re-arms cleanly");
        Intent restart = decideRouted(goal, perc(0.1, true, 5.0, 1), mem);
        Intent.ActionIntent.SelectSlot fresh =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, restart.action());
        assertEquals(POT_SLOT, fresh.slot(), "a fresh episode re-presses the pot key");

        // The throw goes out ON the pot slot, and only a genuinely NEW launch
        // (1 -> 2) confirms — round 1's launch is never phantom-charged.
        Intent rethrow = decideRouted(goal, perc(0.1, true, 5.0, 1), mem);
        assertInstanceOf(Intent.ActionIntent.StartUse.class, rethrow.action());
        for (int i = 0; i < 4; i++) {
            decideRouted(goal, perc(0.1, true, 5.0, 2), mem);
        }
        assertEquals(1, mem.ints("potHeal", 7)[POTS_THROWN],
                "exactly the new launch confirms; no phantom from round 1");
        assertEquals(0, mem.ints("potHeal", 7)[GAVE_UP], "no spurious give-up");
    }

    /**
     * A mid-episode hand seizure WITHOUT an unlatch (a higher-utility exclusive
     * goal steals ticks and its request moves the ledger): the rethrow loop's
     * "pot still in hand" assumption is broken, and the hand machine silently
     * drops a StartUse whose slot disagrees with its ledger. Phase 2 must
     * re-press the pot key — one tick — instead of burning the fail streak
     * throwing blind.
     */
    @Test
    void midEpisodeHandSeizureReswapsBeforeThrowing() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(8L);

        decideRouted(goal, perc(0.1, true, 5.0, 0), mem); // phase 0 -> 2, swap shipped
        mem.hand.intendedSlot = WEAPON_SLOT; // the seizing goal moved the hand

        Intent reswap = goal.decide(perc(0.1, true, 5.0, 0), mem);
        Intent.ActionIntent.SelectSlot sel =
                assertInstanceOf(Intent.ActionIntent.SelectSlot.class, reswap.action(),
                        "re-presses the pot key instead of throwing blind");
        assertEquals(POT_SLOT, sel.slot());
        assertEquals(2, mem.ints("potHeal", 7)[PHASE], "still the throw phase");

        mem.hand.intendedSlot = POT_SLOT; // the route moves the ledger that same tick
        Intent thrown = goal.decide(perc(0.1, true, 5.0, 0), mem);
        assertInstanceOf(Intent.ActionIntent.StartUse.class, thrown.action(),
                "the next phase-2 tick throws for real");
    }

    /**
     * With a perfectly-confirming server (every use-item spawns a pot next tick),
     * the episode spends exactly {@code splashCap} CONFIRMED throws, swaps back
     * once, and gives up durably when health never recovers.
     */
    @Test
    void splashCapCountsConfirmedThrowsPerEpisode() {
        int cap = 2;
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, cap));
        BrainMemory mem = new BrainMemory(3L);

        int launched = 0;
        int startUses = 0;
        int weaponSwaps = 0;
        for (int tick = 0; tick < 200 && weaponSwaps == 0; tick++) {
            Perception p = perc(0.1, true, 5.0, launched);
            Intent intent = decideRouted(goal, p, mem);
            assertTrue(mem.ints("potHeal", 7)[POTS_THROWN] <= cap,
                    "potsThrown never exceeds the splash cap");
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                startUses++;
                launched++; // the server confirms; visible next tick
            } else if (intent.action() instanceof Intent.ActionIntent.SelectSlot sel
                    && sel.slot() == WEAPON_SLOT) {
                weaponSwaps++;
            }
        }

        assertEquals(cap, startUses, "the episode throws exactly splashCap confirmed pots");
        assertEquals(1, weaponSwaps, "then swaps back to the weapon exactly once");
        assertEquals(1, mem.ints("potHeal", 7)[GAVE_UP],
                "cap spent without recovery -> durable give-up");
        assertEquals(0.0, goal.utility(perc(0.1, true, 5.0, launched)),
                "still latched OFF while below trigger — no re-throw");
    }

    /**
     * A same-speed chaser holds the gap constant, so the {@code RETREAT_DISTANCE}
     * gate never becomes true. The phase-0 retreat must still time out and reach the
     * throw phase within the tick cap, or the boxer (exclusive + suppressesAttack)
     * back-pedals forever and never heals.
     */
    @Test
    void retreatTimesOutAndThrowsWhenChaserHoldsDistance() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(4L);
        // Distance pinned BELOW the retreat radius (4.5): the gap never opens.
        Perception pinned = perc(0.1, true, 3.0, 0);

        boolean threw = false;
        for (int tick = 0; tick < PotHealGoal.RETREAT_TICK_CAP + 15 && !threw; tick++) {
            Intent intent = decideRouted(goal, pinned, mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                threw = true;
            }
        }
        assertTrue(threw, "retreat must time out and reach the throw phase despite constant distance");
    }

    /**
     * The heal weave inside the kite ring (4.5 ≤ 5.0 ≤ 7.5): every juke tick is
     * a PURE unit tangent (heading·away = 0 — no radial creep), and the side
     * holds walk the 2,3,4,5,5,4,3,2 palindrome from a seed-drawn phase — every
     * hold in [2,5], at least 3 distinct lengths, never one fixed metronome
     * period. Side balance: with sides flipping per hold, any 8 consecutive
     * complete holds cover the whole palindrome, and each side's four holds sum
     * to 2+4+5+3 = 3+5+4+2 = 14 ticks — the weave cannot drift laterally over
     * its period, whatever the seed phase.
     */
    @Test
    void weaveOrbitsOnBalancedVariedHolds() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 36));
        BrainMemory mem = new BrainMemory(5L);

        int launched = 0;
        List<Double> jukeZ = new ArrayList<>();
        for (int tick = 0; tick < 200; tick++) {
            int phaseBefore = mem.ints("potHeal", 7)[PHASE];
            Intent intent = decideRouted(goal, perc(0.1, true, 5.0, launched), mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                launched++; // confirm every throw so the cycle keeps looping
            }
            if (phaseBefore == 2 || phaseBefore == 3) {
                // healJuke ticks with awayDir = (-1,0,0) in-ring: x = 0 (no
                // radial term); z = ∓1 carries the weave side (never 0).
                assertEquals(1.0, intent.moveDirWorld().length(), 1.0E-9, "unit heading");
                assertEquals(0.0, intent.moveDirWorld().dot(new Vec3d(-1, 0, 0)), 1.0E-9,
                        "no radial creep inside the kite ring");
                jukeZ.add(intent.moveDirWorld().z());
            }
        }

        // Group consecutive same-side juke ticks into runs = the hold lengths.
        List<Integer> holds = new ArrayList<>();
        int run = 1;
        for (int i = 1; i < jukeZ.size(); i++) {
            if (Math.signum(jukeZ.get(i)) == Math.signum(jukeZ.get(i - 1))) {
                run++;
            } else {
                holds.add(run);
                run = 1;
            }
        }
        holds.remove(0); // the first run may be truncated by collection start
        assertTrue(holds.size() >= 10, "enough flips to judge the cadence: " + holds.size());
        assertTrue(holds.stream().allMatch(h -> h >= 2 && h <= 5),
                "every hold in [2,5]: " + holds);
        assertTrue(holds.stream().distinct().count() >= 3,
                "the hold length varies (never a metronome): " + holds);

        // One full palindrome period, starting at a flip boundary: sides
        // alternate per hold, so evens vs odds ARE the two sides.
        int side0 = holds.get(0) + holds.get(2) + holds.get(4) + holds.get(6);
        int side1 = holds.get(1) + holds.get(3) + holds.get(5) + holds.get(7);
        assertEquals(14, side0, "one side's palindrome share: " + holds);
        assertEquals(14, side1, "the other side's palindrome share: " + holds);
    }

    /**
     * The kite ring bounds the retreat instead of a monotone march: below the
     * 4.5 inner edge the weave still OPENS distance (heading·away =
     * 0.35/√(1+0.35²) = +0.33035), between 4.5 and 7.5 it orbits (0), and
     * beyond 7.5 it CLOSES back toward the target (−0.33035). Without the far
     * band, back-to-back heal episodes (each re-trigger skips phase 0 once the
     * gap is open) drifted the boxer off the tester arena to a fatal fall.
     */
    @Test
    void kiteRingBandsTheRadialDrift() {
        record Band(double distance, double expectedAwayDot) {}
        List<Band> bands = List.of(
                new Band(4.0, 0.33035),   // inside the inner edge: keep opening
                new Band(5.0, 0.0),       // in the ring: pure orbit
                new Band(8.0, -0.33035)); // past the outer edge: close back in

        for (Band band : bands) {
            PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 36));
            BrainMemory mem = new BrainMemory(6L);
            int launched = 0;
            int jukeTicks = 0;
            // 60 ticks clears the phase-0 retreat cap (16) even pinned close.
            for (int tick = 0; tick < 60; tick++) {
                int phaseBefore = mem.ints("potHeal", 7)[PHASE];
                Intent intent = decideRouted(goal, perc(0.1, true, band.distance(), launched), mem);
                if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                    launched++;
                }
                if (phaseBefore == 2 || phaseBefore == 3) {
                    jukeTicks++;
                    assertEquals(1.0, intent.moveDirWorld().length(), 1.0E-9, "unit heading");
                    assertEquals(band.expectedAwayDot(),
                            intent.moveDirWorld().dot(new Vec3d(-1, 0, 0)), 1.0E-4,
                            "radial drift at distance " + band.distance());
                }
            }
            assertTrue(jukeTicks >= 10, "sampled enough weave ticks at " + band.distance());
        }
    }
}

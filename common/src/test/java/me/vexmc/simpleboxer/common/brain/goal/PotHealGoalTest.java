package me.vexmc.simpleboxer.common.brain.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.BrainMemory;
import me.vexmc.simpleboxer.common.brain.Intent;
import me.vexmc.simpleboxer.common.brain.Perception;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.junit.jupiter.api.Test;

/**
 * Behaviour checks for {@link PotHealGoal}: the utility trigger + hysteresis
 * latch, its exclusivity, and the retreat -> swap -> throw-at-feet -> wait ->
 * repeat/swap-back finite state machine.
 */
class PotHealGoalTest {

    // Items.DEFAULT slot layout.
    private static final int POT_SLOT = 2;
    private static final int WEAPON_SLOT = 0;

    /** A self-heal profile: arm at 6 HP, recover at 18 HP, spend at most {@code cap} pots. */
    private static BoxerSettings settings(boolean enabled, boolean invincible, int cap) {
        return BoxerSettings.DEFAULTS
                .withInvincible(invincible)
                .withSelfHeal(new BoxerSettings.SelfHeal(enabled, 6.0, 18.0, cap));
    }

    /** Perception with a live target: health as a [0,1] fraction, pot availability, and range. */
    private static Perception perc(double healthPct, boolean hasPots, double distance) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                healthPct, 1.0, Perception.UseItemState.NONE, false);
        Perception.TargetState target = new Perception.TargetState(
                distance, 64, 0, 65.6, Vec3d.ZERO,
                90.0, 0.0, distance, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, hasPots, true, true, 0);
        return new Perception(self, target, Perception.TerrainView.OPEN,
                inv, Perception.CombatState.IDLE, 0);
    }

    private static Perception noTarget(double healthPct) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false,
                healthPct, 1.0, Perception.UseItemState.NONE, false);
        Perception.InventoryView inv = new Perception.InventoryView(
                true, true, true, true, true, 0);
        return new Perception(self, null, Perception.TerrainView.OPEN,
                inv, Perception.CombatState.IDLE, 0);
    }

    // ---- (a) utility is zero when the routine cannot / should not fire --------

    @Test
    void utilityZeroWhenIneligible() {
        // Invincible: never heals even at 1 HP.
        PotHealGoal invincible = new PotHealGoal(() -> settings(true, true, 6));
        assertEquals(0.0, invincible.utility(perc(0.05, true, 3.0)));
        assertFalse(invincible.exclusive(perc(0.05, true, 3.0)));

        // Self-heal disabled.
        PotHealGoal disabled = new PotHealGoal(() -> settings(false, false, 6));
        assertEquals(0.0, disabled.utility(perc(0.05, true, 3.0)));

        // No pots in the kit.
        PotHealGoal noPots = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, noPots.utility(perc(0.05, false, 3.0)));

        // No target to disengage from.
        PotHealGoal solo = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, solo.utility(noTarget(0.05)));

        // Eligible but healthy (full HP is well above trigger).
        PotHealGoal healthy = new PotHealGoal(() -> settings(true, false, 6));
        assertEquals(0.0, healthy.utility(perc(1.0, true, 3.0)));
    }

    // ---- (b) high utility + exclusive when mortal & low; hysteresis holds -----

    @Test
    void utilityHighAndExclusiveWhenMortalAndLow() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));

        // 2 HP (0.1 * 20) is well under the 6 HP trigger: strong, exclusive.
        double u = goal.utility(perc(0.1, true, 3.0));
        assertTrue(u > 0.5, "low-health utility should out-score ordinary engage, was " + u);
        assertTrue(u <= PotHealGoal.MAX_UTILITY);
        assertTrue(goal.exclusive(perc(0.1, true, 3.0)), "a healing boxer must hard-seize control");
    }

    @Test
    void hysteresisKeepsHealingUntilResumeHealth() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));

        // Arm the latch at low health.
        assertTrue(goal.utility(perc(0.1, true, 3.0)) > 0.5);

        // 12 HP: above the 6 HP trigger but below the 18 HP resume line. Because
        // the latch is armed, it must STILL be healing (utility > 0, exclusive).
        double mid = goal.utility(perc(0.6, true, 3.0));
        assertTrue(mid > 0.0, "latch must hold between trigger and resume, was " + mid);
        assertTrue(goal.exclusive(perc(0.6, true, 3.0)));

        // 18 HP reaches the resume line: the latch releases, utility falls to 0.
        assertEquals(0.0, goal.utility(perc(0.9, true, 3.0)));
        assertFalse(goal.exclusive(perc(0.9, true, 3.0)));
    }

    // ---- (c) the retreat -> swap -> throw -> wait -> repeat/swap-back FSM ------

    @Test
    void fsmRetreatsSwapsThrowsWaitsAndReleasesOnRecovery() {
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, 6));
        BrainMemory mem = new BrainMemory(1L);

        // Phase 0: too close (< 4.5) — keep fleeing, do not advance.
        Intent flee = goal.decide(perc(0.1, true, 2.0), mem);
        assertEquals(new Vec3d(-1, 0, 0), flee.moveDirWorld(), "flee heading points away from the target");
        assertTrue(flee.wantSprint(), "sprint away while creating space");
        assertInstanceOf(Intent.FacingIntent.FaceMove.class, flee.facing());
        assertEquals(0, mem.ints("potHeal", 3)[0], "still too close, stay in phase 0");

        // Phase 0 -> 1 once we have opened up past the retreat distance.
        goal.decide(perc(0.1, true, 5.0), mem);
        assertEquals(1, mem.ints("potHeal", 3)[0]);

        // Phase 1: swap to the pot slot.
        Intent swap = goal.decide(perc(0.1, true, 5.0), mem);
        assertInstanceOf(Intent.ActionIntent.SelectSlot.class, swap.action());
        assertEquals(POT_SLOT, ((Intent.ActionIntent.SelectSlot) swap.action()).slot());
        assertEquals(2, mem.ints("potHeal", 3)[0]);

        // Phase 2: throw the splash pot at our own feet, standing still.
        Intent throw1 = goal.decide(perc(0.1, true, 5.0), mem);
        assertInstanceOf(Intent.ActionIntent.StartUse.class, throw1.action());
        assertEquals(Vec3d.ZERO, throw1.moveDirWorld(), "stand still so the splash lands on us");
        assertInstanceOf(Intent.FacingIntent.AimAt.class, throw1.facing());
        Intent.FacingIntent.AimAt aim = (Intent.FacingIntent.AimAt) throw1.facing();
        assertEquals(0.0, aim.x());
        assertEquals(63.5, aim.y(), "aim DOWN, half a block below eye/foot height");
        assertEquals(0.0, aim.z());
        assertEquals(1, mem.ints("potHeal", 3)[1], "potsThrown incremented");
        assertEquals(3, mem.ints("potHeal", 3)[0]);

        // Phase 3: wait out the cloud. Health is still low, so after the timer it
        // loops back to phase 1 to throw another pot.
        for (int i = 0; i < 9; i++) {
            Intent wait = goal.decide(perc(0.1, true, 5.0), mem);
            assertEquals(Vec3d.ZERO, wait.moveDirWorld());
            assertInstanceOf(Intent.FacingIntent.AimAt.class, wait.facing());
            assertEquals(3, mem.ints("potHeal", 3)[0], "still waiting");
        }
        goal.decide(perc(0.1, true, 5.0), mem); // 10th wait tick: timer hits 0
        assertEquals(1, mem.ints("potHeal", 3)[0], "still low -> throw another");

        // Second throw (phase 1 -> 2).
        goal.decide(perc(0.1, true, 5.0), mem); // phase 1: swap
        Intent throw2 = goal.decide(perc(0.1, true, 5.0), mem); // phase 2: throw
        assertInstanceOf(Intent.ActionIntent.StartUse.class, throw2.action());
        assertEquals(2, mem.ints("potHeal", 3)[1], "potsThrown == 2");

        // Now recover: raise health to the resume line DURING the wait so the
        // timer expiry routes to phase 4 (swap back) instead of another throw.
        for (int i = 0; i < 10; i++) {
            goal.decide(perc(0.95, true, 5.0), mem); // 19 HP >= 18 resume
        }
        assertEquals(4, mem.ints("potHeal", 3)[0], "recovered -> done phase");

        // Phase 4: swap back to the weapon, reset counters, drop the latch.
        Intent back = goal.decide(perc(0.95, true, 5.0), mem);
        assertInstanceOf(Intent.ActionIntent.SelectSlot.class, back.action());
        assertEquals(WEAPON_SLOT, ((Intent.ActionIntent.SelectSlot) back.action()).slot());
        assertEquals(0, mem.ints("potHeal", 3)[0], "phase reset");
        assertEquals(0, mem.ints("potHeal", 3)[1], "potsThrown reset");

        // Latch released: fully recovered, utility back to 0.
        assertEquals(0.0, goal.utility(perc(0.95, true, 5.0)));
    }

    @Test
    void fsmRespectsSplashCapWhenHealthNeverRecovers() {
        int cap = 2;
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, cap));
        BrainMemory mem = new BrainMemory(2L);

        int startUses = 0;
        int maxPotsThrown = 0;
        int weaponSwaps = 0;

        // Drive constant low health at open range. Count throws until the FIRST
        // swap-back — that first healing episode must spend exactly `cap` pots.
        for (int tick = 0; tick < 200 && weaponSwaps == 0; tick++) {
            Intent intent = goal.decide(perc(0.1, true, 5.0), mem);
            int[] st = mem.ints("potHeal", 3);
            maxPotsThrown = Math.max(maxPotsThrown, st[1]);
            assertTrue(st[1] <= cap, "potsThrown must never exceed the splash cap");

            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                startUses++;
            } else if (intent.action() instanceof Intent.ActionIntent.SelectSlot sel
                    && sel.slot() == WEAPON_SLOT) {
                weaponSwaps++;
            }
        }

        assertEquals(cap, startUses, "the episode throws exactly splashCap pots");
        assertEquals(cap, maxPotsThrown, "potsThrown peaks at the cap");
        assertEquals(1, weaponSwaps, "then swaps back to the weapon exactly once");
    }

    /**
     * The splashCap is an EPISODE budget. When health never recovers, the boxer
     * throws exactly {@code splashCap} pots and then GIVES UP durably: {@code utility}
     * must fall to 0 and stay there while below trigger, so the arbiter re-engages
     * instead of the routine re-latching and looping another full batch forever.
     * Driven like the real arbiter: {@code decide} runs only on ticks where
     * {@code utility > 0}.
     */
    @Test
    void splashCapAppliesPerEpisodeAndDoesNotRelatchWhileStillLow() {
        int cap = 2;
        PotHealGoal goal = new PotHealGoal(() -> settings(true, false, cap));
        BrainMemory mem = new BrainMemory(3L);
        Perception low = perc(0.1, true, 5.0); // 2 HP, well below trigger, never recovers

        int potsThrown = 0;
        boolean gaveUp = false;
        for (int tick = 0; tick < 500; tick++) {
            if (goal.utility(low) <= 0.0) {
                gaveUp = true; // arbiter would hand control back to Engage here
                continue;      // ...so the routine does NOT decide this tick
            }
            Intent intent = goal.decide(low, mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                potsThrown++;
            }
        }

        assertEquals(cap, potsThrown, "exactly splashCap pots across the whole episode, no re-latch");
        assertTrue(gaveUp, "utility must drop to 0 after the cap is spent");
        assertEquals(0.0, goal.utility(low), "still latched OFF while below trigger — no re-throw");
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
        Perception pinned = perc(0.1, true, 3.0);

        boolean threw = false;
        for (int tick = 0; tick < PotHealGoal.RETREAT_TICK_CAP + 5 && !threw; tick++) {
            Intent intent = goal.decide(pinned, mem);
            if (intent.action() instanceof Intent.ActionIntent.StartUse) {
                threw = true;
            }
        }
        assertTrue(threw, "retreat must time out and reach the throw phase despite constant distance");
    }
}

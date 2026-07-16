package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Exercises the four load-bearing behaviours of the arbiter: raw highest-utility
 * selection, dwell hysteresis, the exclusive latch, and the all-zero idle
 * fallback (including that {@code decide} never fires on a zero-utility goal).
 */
class ArbiterTest {

    /** A crafted goal whose utility and exclusivity can be flipped mid-test. */
    private static final class TestGoal implements Goal {
        final String id;
        double util;
        final int dwell;
        final double commit;
        boolean exclusive;
        int decideCalls;

        TestGoal(String id, double util, int dwell, double commit, boolean exclusive) {
            this.id = id;
            this.util = util;
            this.dwell = dwell;
            this.commit = commit;
            this.exclusive = exclusive;
        }

        @Override
        public @NotNull String id() {
            return id;
        }

        @Override
        public double utility(@NotNull Perception perception) {
            return util;
        }

        @Override
        public @NotNull Intent decide(@NotNull Perception perception, @NotNull BrainMemory memory) {
            decideCalls++;
            return Intent.IDLE;
        }

        @Override
        public int minDwellTicks() {
            return dwell;
        }

        @Override
        public double commitBonus() {
            return commit;
        }

        @Override
        public boolean exclusive(@NotNull Perception perception) {
            return exclusive;
        }
    }

    /** Goals ignore the snapshot here, so any well-formed Perception will do. */
    private static Perception perception() {
        return new Perception(
                new Perception.SelfState(
                        0, 0, 0, Vec3d.ZERO, true, false, 1.0, 1.0,
                        Perception.UseItemState.NONE, false, 0.1, -1),
                null,
                Perception.TerrainView.OPEN,
                Perception.InventoryView.EMPTY,
                Perception.CombatState.IDLE,
                0);
    }

    // (a) Highest utility wins outright with no incumbent in play.
    @Test
    void highestUtilityWins() {
        TestGoal low = new TestGoal("low", 0.3, 0, 0.0, false);
        TestGoal high = new TestGoal("high", 0.9, 0, 0.0, false);
        Arbiter arb = new Arbiter(List.of(low, high));
        BrainMemory mem = new BrainMemory(0L);

        Arbiter.Result r = arb.select(perception(), mem);

        assertSame(high, r.goal());
        assertEquals("high", mem.incumbentGoal);
        assertEquals(0, mem.dwellTicks, "a fresh winner starts its dwell at zero");
        assertEquals(1, high.decideCalls);
        assertEquals(0, low.decideCalls, "the loser is never asked to decide");
    }

    // (b) An incumbent with a commit bonus and minDwell=5 holds against a
    //     marginally-higher rival for five ticks, then is replaced.
    @Test
    void dwellHoldsThenReleases() {
        TestGoal inc = new TestGoal("inc", 0.50, 5, 0.10, false);   // effective 0.60
        TestGoal rival = new TestGoal("rival", 0.65, 0, 0.0, false); // beats by 0.05 < LARGE_MARGIN
        Arbiter arb = new Arbiter(List.of(inc, rival));
        BrainMemory mem = new BrainMemory(0L);

        // Seed 'inc' as the just-crowned incumbent at the start of its dwell.
        mem.incumbentGoal = "inc";
        mem.dwellTicks = 0;

        // Ticks 1..5: dwell not yet served, the marginal rival cannot pre-empt.
        for (int tick = 1; tick <= 5; tick++) {
            Arbiter.Result r = arb.select(perception(), mem);
            assertSame(inc, r.goal(), "incumbent holds during dwell (tick " + tick + ")");
            assertEquals(tick, mem.dwellTicks, "held ticks accumulate");
        }

        // Tick 6: dwell served (5 >= 5), the higher rival finally wins.
        Arbiter.Result r = arb.select(perception(), mem);
        assertSame(rival, r.goal(), "once dwell is served the higher rival takes over");
        assertEquals("rival", mem.incumbentGoal);
        assertEquals(0, mem.dwellTicks, "the switch resets dwell");
    }

    // (c) An exclusive goal with positive utility wins even against a higher
    //     score, and holds until its own utility drops.
    @Test
    void exclusiveLatchSeizesUntilItDrops() {
        TestGoal heal = new TestGoal("heal", 0.20, 0, 0.0, true); // low score, but exclusive
        TestGoal chase = new TestGoal("chase", 0.90, 0, 0.0, false);
        Arbiter arb = new Arbiter(List.of(heal, chase));
        BrainMemory mem = new BrainMemory(0L);

        // While exclusive+positive, the low-scoring latch beats the higher chase.
        Arbiter.Result r1 = arb.select(perception(), mem);
        assertSame(heal, r1.goal(), "exclusive latch outranks a higher non-exclusive goal");
        assertEquals("heal", mem.incumbentGoal);

        Arbiter.Result r2 = arb.select(perception(), mem);
        assertSame(heal, r2.goal(), "latch keeps control while still exclusive");
        assertEquals(1, mem.dwellTicks, "holding the latch increments dwell");

        // Latch releases the moment its utility collapses to zero.
        int healDecidesBefore = heal.decideCalls;
        heal.util = 0.0;
        Arbiter.Result r3 = arb.select(perception(), mem);
        assertSame(chase, r3.goal(), "a zero-utility latch yields to the field");
        assertEquals("chase", mem.incumbentGoal);
        assertEquals(healDecidesBefore, heal.decideCalls,
                "the latch is not asked to decide on the tick it hit zero utility");
    }

    // (d) Every goal at zero utility yields the idle result with no NPE and no
    //     decide() call on any real goal.
    @Test
    void allZeroFallsBackToIdle() {
        TestGoal a = new TestGoal("a", 0.0, 0, 0.0, false);
        TestGoal b = new TestGoal("b", 0.0, 0, 0.0, false);
        Arbiter arb = new Arbiter(List.of(a, b));
        BrainMemory mem = new BrainMemory(0L);

        Arbiter.Result r = arb.select(perception(), mem);

        assertNotNull(r);
        assertEquals("idle", r.goal().id());
        assertSame(Intent.IDLE, r.intent());
        assertEquals("idle", mem.incumbentGoal);
        assertEquals(0, a.decideCalls, "zero-utility goals are never asked to decide");
        assertEquals(0, b.decideCalls, "zero-utility goals are never asked to decide");
    }

    // A negative/garbage utility is clamped to "wants nothing", not a live bid.
    @Test
    void negativeUtilityIsTreatedAsIdle() {
        TestGoal bad = new TestGoal("bad", -5.0, 0, 0.0, false);
        Arbiter arb = new Arbiter(List.of(bad));
        BrainMemory mem = new BrainMemory(0L);

        Arbiter.Result r = arb.select(perception(), mem);

        assertEquals("idle", r.goal().id());
        assertEquals(0, bad.decideCalls);
    }
}

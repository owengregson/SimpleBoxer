package me.vexmc.simpleboxer.common.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.simpleboxer.common.brain.Perception.CombatState;
import me.vexmc.simpleboxer.common.brain.Perception.InventoryView;
import me.vexmc.simpleboxer.common.brain.Perception.SelfState;
import me.vexmc.simpleboxer.common.brain.Perception.TargetState;
import me.vexmc.simpleboxer.common.brain.Perception.TerrainView;
import me.vexmc.simpleboxer.common.brain.Perception.UseItemState;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

class AntiStuckTest {

    private final AntiStuck antiStuck = new AntiStuck();

    /** A boxer at (x,y,z) with a foe iff {@code hasTarget}, reporting {@code hcol}. */
    private static Perception perception(double x, double y, double z,
            boolean hasTarget, boolean hcol) {
        SelfState self = new SelfState(x, y, z, Vec3d.ZERO, true, hcol,
                1.0, 1.0, UseItemState.NONE, false);
        TargetState target = hasTarget
                ? new TargetState(x + 3, y, z, y + 1.62, Vec3d.ZERO, 0.0, 0.0, 0.0, 3.0, false)
                : null;
        return new Perception(self, target, TerrainView.OPEN,
                InventoryView.EMPTY, CombatState.IDLE, 0);
    }

    @Test
    void pinnedBoxerFlagsStuckThenEscalatesToReroute() {
        BrainMemory mem = new BrainMemory(1L);
        // Has a foe (intends to move) and the integrator clamped its move.
        Perception p = perception(0.5, 64, 0.5, true, true);

        // Not stuck until the sustained window elapses.
        for (int tick = 0; tick < 2; tick++) {
            mem.recordPosition(0.5, 0.5); // stays put -> zero net displacement
            assertFalse(antiStuck.isStuck(p, mem), "should not flag before the window");
            assertFalse(antiStuck.shouldReroute(mem));
        }

        // Third qualifying tick trips the flag.
        mem.recordPosition(0.5, 0.5);
        assertTrue(antiStuck.isStuck(p, mem), "sustained no-progress + collision -> stuck");
        assertFalse(antiStuck.shouldReroute(mem), "one detour window is not yet a reroute");

        // Keep it pinned; eventually a lateral detour is deemed to have failed.
        boolean rerouted = false;
        for (int tick = 0; tick < 20 && !rerouted; tick++) {
            mem.recordPosition(0.5, 0.5);
            antiStuck.isStuck(p, mem);
            rerouted = antiStuck.shouldReroute(mem);
        }
        assertTrue(rerouted, "persistent stuck must escalate to a reroute request");
    }

    @Test
    void goodProgressIsNeverStuck() {
        BrainMemory mem = new BrainMemory(1L);
        Perception p = perception(0.5, 64, 0.5, true, true); // even with a collision flag

        // Fill the whole progress window with healthy travel.
        for (int tick = 0; tick < 12; tick++) {
            mem.recordPosition(0.5 + tick * 0.5, 0.5); // healthy net travel
            assertFalse(antiStuck.isStuck(p, mem), "moving boxer is not stuck");
        }
        assertFalse(antiStuck.shouldReroute(mem));
    }

    @Test
    void noTargetMeansNoStuckEvenWhenColliding() {
        BrainMemory mem = new BrainMemory(1L);
        Perception idle = perception(0.5, 64, 0.5, false, true); // no foe -> no move intent

        for (int tick = 0; tick < 10; tick++) {
            mem.recordPosition(0.5, 0.5);
            assertFalse(antiStuck.isStuck(idle, mem));
        }
        assertFalse(antiStuck.shouldReroute(mem));
    }

    @Test
    void detourPicksAClearPerpendicularHeading() {
        BrainMemory mem = new BrainMemory(1L);
        // Floor to stand on; a 2-block-tall wall directly ahead in +Z so the
        // intended heading is genuinely blocked while both flanks (±X) are open.
        FakeWorld world = FakeWorld.floorAt(64).wall(-1, 64, 1, 1, 65, 1);
        Perception p = perception(0.5, 64, 0.5, true, true);
        MoveHeading intended = new MoveHeading(new Vec3d(0, 0, 1)); // +Z into the wall

        MoveHeading detour = antiStuck.detour(p, intended, world, mem);

        assertFalse(detour.isStill(), "a stuck boxer must get a heading to try");
        // Roughly perpendicular to the intended heading.
        double dot = detour.dirWorld().horizontalNormalized().dot(intended.dirWorld());
        assertTrue(Math.abs(dot) < 1.0E-6, "detour should be perpendicular to intent, dot=" + dot);
        // The chosen side is not walled.
        assertFalse(NavGeometry.wallAhead(world,
                NavGeometry.playerBox(p.self().x(), p.self().y(), p.self().z()),
                detour.dirWorld(), NavGeometry.LOOK_AHEAD), "chosen detour must be clear");
        assertTrue(detour.speedScale() < 1.0, "a detour eases the throttle");
    }

    @Test
    void detourAlternatesSideAcrossCalls() {
        BrainMemory mem = new BrainMemory(1L);
        FakeWorld world = FakeWorld.floorAt(64); // both flanks open
        Perception p = perception(0.5, 64, 0.5, true, true);
        MoveHeading intended = new MoveHeading(new Vec3d(0, 0, 1));

        MoveHeading first = antiStuck.detour(p, intended, world, mem);
        MoveHeading second = antiStuck.detour(p, intended, world, mem);

        // Opposite sides on successive ticks -> the two headings are reversed.
        double dot = first.dirWorld().horizontalNormalized()
                .dot(second.dirWorld().horizontalNormalized());
        assertTrue(dot < -0.99, "successive detours should wiggle to opposite sides, dot=" + dot);
    }

    @Test
    void detourBacksOffWhenBothFlanksWalled() {
        BrainMemory mem = new BrainMemory(1L);
        // A tall wall wrapping both ±X flanks: every perpendicular is blocked.
        FakeWorld world = FakeWorld.floorAt(64)
                .wall(1, 64, -1, 1, 65, 1)   // +X flank
                .wall(-1, 64, -1, -1, 65, 1); // -X flank
        Perception p = perception(0.5, 64, 0.5, true, true);
        MoveHeading intended = new MoveHeading(new Vec3d(0, 0, 1)); // facing +Z

        MoveHeading detour = antiStuck.detour(p, intended, world, mem);

        // Cornered -> reverse straight back (−Z).
        assertTrue(detour.dirWorld().z() < -0.99, "cornered boxer should back away, got " + detour.dirWorld());
    }

    @Test
    void detourOnStillIntentIsStill() {
        BrainMemory mem = new BrainMemory(1L);
        FakeWorld world = FakeWorld.floorAt(64);
        Perception p = perception(0.5, 64, 0.5, true, true);

        assertTrue(antiStuck.detour(p, MoveHeading.STILL, world, mem).isStill());
    }
}

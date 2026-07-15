package me.vexmc.simpleboxer.common.brain;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.brain.Intent.ActionIntent;
import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockhitControllerTest {

    private final BlockhitController blockhit = new BlockhitController();

    private static Perception melee(boolean hasSword) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false, 1.0, 1.0, Perception.UseItemState.NONE, false);
        Perception.TargetState t = new Perception.TargetState(
                2, 64, 0, 65.62, Vec3d.ZERO, 0, 0, 0, 2.0, false);
        Perception.InventoryView inv = new Perception.InventoryView(hasSword, false, false, false, false, 0);
        return new Perception(self, t, Perception.TerrainView.OPEN, inv, Perception.CombatState.IDLE, 0);
    }

    @Test
    void tapsABlockThenReleasesInAttackGaps() {
        BrainMemory mem = new BrainMemory(1L);
        Perception p = melee(true);
        boolean sawStart = false;
        boolean sawRelease = false;
        for (int i = 0; i < 20; i++) {
            List<ActionIntent> out = new ArrayList<>();
            blockhit.apply(p, true, true, false, mem, out);
            for (ActionIntent a : out) {
                if (a instanceof ActionIntent.StartUse) {
                    sawStart = true;
                }
                if (a instanceof ActionIntent.ReleaseUse) {
                    sawRelease = true;
                }
            }
        }
        assertTrue(sawStart, "blockhit taps a sword block in the gaps");
        assertTrue(sawRelease, "and releases it again");
    }

    @Test
    void doesNothingWhileAttackingEveryTick() {
        BrainMemory mem = new BrainMemory(1L);
        Perception p = melee(true);
        for (int i = 0; i < 20; i++) {
            List<ActionIntent> out = new ArrayList<>();
            // an attack fires every tick (high CPS) -> never room to block
            boolean emitted = blockhit.apply(p, true, true, true, mem, out);
            assertFalse(emitted && out.stream().anyMatch(a -> a instanceof ActionIntent.StartUse),
                    "no block is raised on a ticking-attack frame");
        }
    }

    @Test
    void inertWithoutASwordOrWhenDisabled() {
        BrainMemory mem = new BrainMemory(1L);
        for (int i = 0; i < 12; i++) {
            List<ActionIntent> out = new ArrayList<>();
            assertFalse(blockhit.apply(melee(false), true, true, false, mem, out), "no sword -> no block");
            assertTrue(out.isEmpty());
        }
        for (int i = 0; i < 12; i++) {
            List<ActionIntent> out = new ArrayList<>();
            assertFalse(blockhit.apply(melee(true), false, true, false, mem, out), "disabled -> no block");
        }
    }
}

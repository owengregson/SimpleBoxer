package me.vexmc.simpleboxer.common.brain;

import me.vexmc.simpleboxer.common.physics.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BlockhitControllerTest {

    private final BlockhitController blockhit = new BlockhitController();

    private static Perception melee(boolean hasSword) {
        Perception.SelfState self = new Perception.SelfState(
                0, 64, 0, Vec3d.ZERO, true, false, 1.0, 1.0, Perception.UseItemState.NONE, false,
                0.1, -1, 20.0, 0, 3.0, 1.0, false);
        Perception.TargetState t = new Perception.TargetState(
                2, 64, 0, 65.62, Vec3d.ZERO, 0, 0, 0, 2.0, false);
        Perception.InventoryView inv = new Perception.InventoryView(hasSword, false, false, false, false, 0);
        return new Perception(self, t, Perception.TerrainView.OPEN, inv, Perception.CombatState.IDLE, 0);
    }

    @Test
    void asksForATapEverySixthEligibleTick() {
        BrainMemory mem = new BrainMemory(1L);
        Perception p = melee(true);
        // The counter advances every eligible call; the ask fires at every
        // multiple of the 6-tick period: calls 6, 12, 18.
        for (int call = 1; call <= 18; call++) {
            assertEquals(call % 6 == 0, blockhit.desire(p, true, true, false, mem),
                    "call " + call);
        }
    }

    @Test
    void neverAsksWhileAttacksFireEveryTick() {
        BrainMemory mem = new BrainMemory(1L);
        Perception p = melee(true);
        for (int call = 1; call <= 20; call++) {
            assertFalse(blockhit.desire(p, true, true, true, mem),
                    "no tap on a ticking-attack frame, call " + call);
        }
    }

    @Test
    void inertWithoutASwordOrWhenDisabled() {
        BrainMemory mem = new BrainMemory(1L);
        for (int call = 1; call <= 12; call++) {
            assertFalse(blockhit.desire(melee(false), true, true, false, mem), "no sword");
        }
        for (int call = 1; call <= 12; call++) {
            assertFalse(blockhit.desire(melee(true), false, true, false, mem), "disabled");
        }
    }
}

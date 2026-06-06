package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Simulated ping is real, measurable delay: a 400 ms boxer starts flying
 * its knockback ~4 ticks after a 0 ms boxer does, because the velocity
 * packet ages through the perception line before its client integrates it —
 * the same lag a real connection would impose.
 */
public final class LatencySuite {

    private LatencySuite() {}

    public static @NotNull List<TestCase> tests() {
        return List.of(
                new TestCase("latency: zero ping reacts immediately", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 160, 100));
                    Boxer boxer = Arenas.spawn("Wired", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(10);
                        double startZ = context.sync(() -> boxer.player().getLocation().getZ());
                        context.syncRun(() -> boxer.player()
                                .setVelocity(new Vector(0.0, 0.4607, 0.9)));
                        context.awaitTicks(3);
                        double moved = context.sync(
                                () -> boxer.player().getLocation().getZ()) - startZ;
                        context.expect(moved > 0.5,
                                "0ms boxer is already flying (" + moved + ")");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("latency: 400ms ping delays the knock by the one-way", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 190, 100));
                    Boxer boxer = Arenas.spawn("Laggy", center,
                            DifficultyPresets.DUMMY.withPingMs(400));
                    try {
                        context.awaitTicks(15);
                        double startZ = context.sync(() -> boxer.player().getLocation().getZ());
                        context.syncRun(() -> boxer.player()
                                .setVelocity(new Vector(0.0, 0.4607, 0.9)));
                        // One-way = 200 ms = 4 ticks: the perception line is
                        // still holding the packet two ticks in...
                        context.awaitTicks(2);
                        double early = context.sync(
                                () -> boxer.player().getLocation().getZ()) - startZ;
                        context.expect(Math.abs(early) < 0.2,
                                "still parked inside the one-way window (" + early + ")");
                        // ...and the flight arrives after it matures (plus the
                        // action line shipping the movement back).
                        context.awaitUntil(() -> {
                            try {
                                return boxer.player().getLocation().getZ() - startZ > 1.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 40, "the delayed knock to fly");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }));
    }
}

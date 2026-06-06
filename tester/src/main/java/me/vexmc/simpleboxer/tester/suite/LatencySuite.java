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
                        context.awaitUntil(() -> {
                            try {
                                return boxer.player().isOnGround();
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 60, "the boxer to settle");
                        context.awaitTicks(3);
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
                        context.awaitUntil(() -> {
                            try {
                                return boxer.player().isOnGround();
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 60, "the laggy boxer to settle");
                        // A 400ms boxer's teleport confirm takes 8+ ticks
                        // each way; vanilla re-teleports unconfirmed clients
                        // on a 20-tick cycle. Probe AFTER the join turbulence
                        // — a real 400ms player rubber-bands through the
                        // same window.
                        context.awaitTicks(30);
                        double startZ = context.sync(() -> boxer.player().getLocation().getZ());
                        long stamped = System.nanoTime();
                        context.syncRun(() -> boxer.player()
                                .setVelocity(new Vector(0.0, 0.4607, 0.9)));
                        context.note("post-set velocity reads "
                                + context.sync(() -> boxer.player().getVelocity()));
                        // One-way = 200 ms = 4 ticks: the perception line is
                        // still holding the packet two ticks in. The lines
                        // are WALL-clock (network transit is wall time), so
                        // the parked assertion only binds when the two game
                        // ticks really took under the one-way wall window —
                        // a matrix stall's catch-up burst burns ticks faster
                        // than wall and would assert against matured state.
                        context.awaitTicks(2);
                        double early = context.sync(
                                () -> boxer.player().getLocation().getZ()) - startZ;
                        if (System.nanoTime() - stamped < 150_000_000L) {
                            context.expect(Math.abs(early) < 0.2,
                                    "still parked inside the one-way window (" + early + ")");
                        } else {
                            context.note("stall burst skipped the parked-window assertion");
                        }
                        // ...and the flight arrives after the wall-time
                        // maturity (plus the action line shipping movement
                        // back) — 100 ticks bounds a several-second stall.
                        context.awaitUntil(() -> {
                            try {
                                return boxer.player().getLocation().getZ() - startZ > 1.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "the delayed knock to fly");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }));
    }
}

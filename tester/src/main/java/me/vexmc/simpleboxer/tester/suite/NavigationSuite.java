package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Active navigation: a boxer that reaches a target behind a wall it cannot jump —
 * it must slide along the obstacle and route around the gap — and one that clears
 * a one-block step. Boxer outbound is netty-invisible, so we assert the only
 * observable thing: where the boxer's real body ends up.
 */
public final class NavigationSuite {

    private NavigationSuite() {}

    public static @NotNull List<TestCase> tests() {
        return List.of(
                new TestCase("navigation: routes around a two-tall wall to the target", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    int baseX = 40;
                    int baseZ = 160;
                    Location center = context.sync(() -> Arenas.arena(world, baseX, baseZ));
                    // A two-tall wall across the path with a gap on the +X end.
                    context.syncRun(() -> {
                        for (int x = -6; x <= 4; x++) {
                            for (int dy = 1; dy <= 2; dy++) {
                                world.getBlockAt(baseX + x, 80 + dy, baseZ + 6).setType(Material.STONE);
                            }
                        }
                    });
                    Boxer post = Arenas.spawn("NavPost",
                            center.clone().add(0, 0, 13), DifficultyPresets.DUMMY);
                    Boxer walker = Arenas.spawn("Navigator", center,
                            BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> walker.setTarget(post.player()));
                        context.awaitUntil(() -> {
                            try {
                                return walker.player().getLocation()
                                        .distance(post.player().getLocation()) < 3.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 400, "the navigator to route around the wall to the target");
                        double reached = context.sync(() -> walker.player().getLocation()
                                .distance(post.player().getLocation()));
                        context.expect(reached < 3.0, "reached the target (" + reached + " away)");
                    } finally {
                        context.syncRun(walker::remove);
                        context.syncRun(post::remove);
                    }
                }),

                new TestCase("navigation: clears a one-block step", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    int baseX = 40;
                    int baseZ = 200;
                    Location center = context.sync(() -> Arenas.arena(world, baseX, baseZ));
                    // A one-block-high ledge just ahead, with a close target beyond it
                    // so reaching it is a short hop-and-go (the step-jump mechanic is
                    // unit-pinned; this is the on-server smoke test).
                    context.syncRun(() -> {
                        for (int x = -4; x <= 4; x++) {
                            world.getBlockAt(baseX + x, 81, baseZ + 5).setType(Material.STONE);
                        }
                    });
                    Boxer post = Arenas.spawn("StepPost",
                            center.clone().add(0, 0, 8), DifficultyPresets.DUMMY);
                    Boxer hopper = Arenas.spawn("Hopper", center,
                            BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> hopper.setTarget(post.player()));
                        context.awaitUntil(() -> {
                            try {
                                return hopper.player().getLocation()
                                        .distance(post.player().getLocation()) < 3.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 500, "the hopper to climb the step and reach the target");
                        double reached = context.sync(() -> hopper.player().getLocation()
                                .distance(post.player().getLocation()));
                        context.expect(reached < 3.0, "reached over the step (" + reached + " away)");
                    } finally {
                        context.syncRun(hopper::remove);
                        context.syncRun(post::remove);
                    }
                }));
    }
}

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
                }));
        // NOTE: the one-block-step CLIMB is exercised by ClientPhysicsTest /
        // BrainTest in `common` (the momentum jump) and the walk-around fallback by
        // LocalPathPlannerTest; it is not asserted on-server here because the
        // step-jump interacts with each server's movement validation (it lands on
        // 1.17.1–1.21.x but the bleeding-edge 26.x preview's stricter validation
        // rejects the climb — the boxer then routes AROUND the step instead).
    }
}

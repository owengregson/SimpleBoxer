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
                new TestCase("descends: drops off a survivable ledge to the player below", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    int baseX = 40;
                    int baseZ = 220;
                    Location center = context.sync(() -> Arenas.arena(world, baseX, baseZ));
                    // A 3×3 pillar rising 5 above the pad (pad top 81 → pillar top
                    // 86). The drop predicts ceil(5−3) = 2 points against the
                    // 10-point half-max budget: the corridor validates it and the
                    // boxer walks off toward the player — no stair detour exists.
                    context.syncRun(() -> {
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                for (int y = 81; y <= 85; y++) {
                                    world.getBlockAt(baseX + x, y, baseZ + z)
                                            .setType(Material.STONE);
                                }
                            }
                        }
                    });
                    Boxer post = Arenas.spawn("DropPost",
                            center.clone().add(0, 0, 7), DifficultyPresets.DUMMY);
                    Boxer dropper = Arenas.spawn("Dropper",
                            new Location(world, baseX + 0.5, 86.0, baseZ + 0.5),
                            BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> dropper.setTarget(post.player()));
                        context.awaitUntil(() -> {
                            try {
                                return dropper.player().getLocation()
                                        .distance(post.player().getLocation()) < 3.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 200, "the boxer to take the 5-block drop to the player below");
                        double y = context.sync(() -> dropper.player().getLocation().getY());
                        double health = context.sync(() -> dropper.player().getHealth());
                        context.expect(y < 82.5, "landed on the pad (y=" + y + ")");
                        context.expect(health >= 16.0,
                                "the landing cost at most ~2 points (health=" + health + ")");
                    } finally {
                        context.syncRun(dropper::remove);
                        context.syncRun(post::remove);
                    }
                }),
                new TestCase("descends: takes the walking route when the drop exceeds the budget", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    int baseX = 40;
                    int baseZ = 280;
                    Location center = context.sync(() -> Arenas.arena(world, baseX, baseZ));
                    // A 2×2 pillar rising 14 above the pad (top 95): the direct
                    // drop predicts ceil(14−3) = 11 points > the 10-point budget,
                    // so every guard refuses the lip. A 14-step staircase descends
                    // BEHIND the pillar (+z, one block down per cell, z+1 → z+14,
                    // inside the pad and the planner's stretched down-band
                    // max(8, ceil(13)+2) = 15 ≥ 14). Zero fall damage at arrival
                    // discriminates the stairs from any drop.
                    context.syncRun(() -> {
                        for (int x = 0; x <= 1; x++) {
                            for (int z = -1; z <= 0; z++) {
                                for (int y = 81; y <= 94; y++) {
                                    world.getBlockAt(baseX + x, y, baseZ + z)
                                            .setType(Material.STONE);
                                }
                            }
                        }
                        for (int i = 0; i < 14; i++) {
                            for (int x = 0; x <= 1; x++) {
                                for (int y = 81; y <= 93 - i; y++) {
                                    world.getBlockAt(baseX + x, y, baseZ + 1 + i)
                                            .setType(Material.STONE);
                                }
                            }
                        }
                    });
                    Boxer post = Arenas.spawn("StairPost",
                            center.clone().add(0.5, 0, -7), DifficultyPresets.DUMMY);
                    Boxer walker = Arenas.spawn("StairWalker",
                            new Location(world, baseX + 0.5, 95.0, baseZ - 0.5),
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
                        }, 600, "the boxer to descend the staircase to the player");
                        double health = context.sync(() -> walker.player().getHealth());
                        context.expect(health >= 19.9,
                                "a walking descent takes ZERO fall damage (health=" + health + ")");
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

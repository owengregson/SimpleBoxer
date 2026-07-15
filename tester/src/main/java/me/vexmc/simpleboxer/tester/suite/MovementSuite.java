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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The pursuit contract: a boxer is a W-key held to the floor. It obeys the
 * server's live movement attributes (a Speed potion reaches the integrator
 * like the UpdateAttributes packet it stands in for), and it never releases
 * forward in the pocket — releasing there drops sprint and the momentum
 * that survives combos, which is precisely how the first release shipped
 * got boxers combo-locked.
 */
public final class MovementSuite {

    private MovementSuite() {}

    public static @NotNull List<TestCase> tests() {
        return List.of(
                new TestCase("movement: speed potion outruns the unpotioned twin", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 220, 100));
                    // Two lanes, one race: identical brains and settings;
                    // the only difference is server-side Speed IV (×1.8 on
                    // the movement-speed attribute) on one runner.
                    Boxer plainPost = Arenas.spawn("PlainPost",
                            center.clone().add(-6, 0, 16), DifficultyPresets.DUMMY);
                    Boxer speedyPost = Arenas.spawn("SpeedyPost",
                            center.clone().add(6, 0, 16), DifficultyPresets.DUMMY);
                    // cps 0: this is a footrace — a punching racer would
                    // era-knock its finish post away on arrival under the
                    // combat stack and the arrival await would never hold.
                    Boxer plain = Arenas.spawn("PlainRacer",
                            center.clone().add(-6, 0, 0), BoxerSettings.DEFAULTS.withCps(0.0));
                    Boxer speedy = Arenas.spawn("SpeedyRacer",
                            center.clone().add(6, 0, 0), BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> {
                            speedy.player().addPotionEffect(new PotionEffect(
                                    PotionEffectType.SPEED, 20 * 120, 3, false, false));
                            plain.setTarget(plainPost.player());
                            speedy.setTarget(speedyPost.player());
                        });
                        // ~0.505 blocks/tick potioned vs ~0.281 plain: when
                        // the speedster arrives, the twin is still blocks
                        // out. Both progress per TICK, so a stalled matrix
                        // server distorts neither side of the comparison.
                        context.awaitUntil(() -> {
                            try {
                                return speedy.player().getLocation()
                                        .distance(speedyPost.player().getLocation()) < 2.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 200, "the potioned racer to arrive");
                        double remaining = context.sync(() -> plain.player().getLocation()
                                .distance(plainPost.player().getLocation()));
                        context.expect(remaining > 4.0,
                                "Speed IV gapped the unpotioned twin (" + remaining + " left)");
                    } finally {
                        context.syncRun(plain::remove);
                        context.syncRun(speedy::remove);
                        context.syncRun(plainPost::remove);
                        context.syncRun(speedyPost::remove);
                    }
                }),

                new TestCase("movement: the pocket never releases sprint", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 250, 100));
                    Boxer bag = Arenas.spawn("PocketBag",
                            center.clone().add(0, 0, 6), DifficultyPresets.DUMMY);
                    // cps 0: pure pursuit. No landed hits means no vanilla
                    // sprint-attack clears and no knockback moving the bag
                    // — this case isolates the MOVEMENT contract (the old
                    // stop ring released W at 2.5 and parked sprint false).
                    Boxer presser = Arenas.spawn("Presser", center,
                            BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> presser.setTarget(bag.player()));
                        context.awaitUntil(() -> {
                            try {
                                return presser.player().getLocation()
                                        .distance(bag.player().getLocation()) < 1.5;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "presser to press into the pocket");
                        // 30 consecutive in-pocket ticks with the flag held
                        // — fed ONLY by the boxer's own PlayerCommands, so
                        // a single forward release shows immediately.
                        for (int tick = 0; tick < 30; tick++) {
                            context.expect(context.sync(() -> presser.player().isSprinting()),
                                    "sprint held in the pocket (tick " + tick + ")");
                            context.awaitTicks(1);
                        }
                        double distance = context.sync(() -> presser.player().getLocation()
                                .distance(bag.player().getLocation()));
                        context.expect(distance < 2.4,
                                "still pressed on the target (" + distance + ")");
                    } finally {
                        context.syncRun(presser::remove);
                        context.syncRun(bag::remove);
                    }
                }),

                new TestCase("movement: sprint re-arms through landed hits", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 280, 100));
                    Boxer bag = Arenas.spawn("RearmBag",
                            center.clone().add(0, 0, 6), DifficultyPresets.DUMMY);
                    Boxer fighter = Arenas.spawn("Rearmer", center, BoxerSettings.DEFAULTS);
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> fighter.setTarget(bag.player()));
                        context.awaitUntil(() -> {
                            try {
                                return fighter.player().getLocation()
                                        .distance(bag.player().getLocation()) < 3.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "fighter to reach combat range");
                        // Vanilla clears the ATTACKER's sprint flag on each
                        // full-meter sprint hit — under OCM's restored 1.8
                        // hit speed that is EVERY landed punch (and each
                        // knock sends the bag flying, so the fight roams).
                        // A toggle-sprint client re-arms within a tick or
                        // two: across sustained combat the flag may flicker
                        // at hit cadence but must dominate the window and
                        // never stick false — the stale-cache bug parked it
                        // false from the first punch onward.
                        int sprinting = 0;
                        int falseRun = 0;
                        int worstFalseRun = 0;
                        for (int tick = 0; tick < 40; tick++) {
                            if (context.sync(() -> fighter.player().isSprinting())) {
                                sprinting++;
                                falseRun = 0;
                            } else {
                                worstFalseRun = Math.max(worstFalseRun, ++falseRun);
                            }
                            context.awaitTicks(1);
                        }
                        context.expect(sprinting >= 24,
                                "sprint dominates the fight (" + sprinting + "/40 sprinting)");
                        context.expect(worstFalseRun <= 6,
                                "re-arm is prompt (worst gap " + worstFalseRun + " ticks)");
                    } finally {
                        context.syncRun(fighter::remove);
                        context.syncRun(bag::remove);
                    }
                }),

                new TestCase("movement: a boxer that jumps into a wall falls back down",
                        context -> {
                    // WS1c repro: a boxer sprints at a 1-block step fronting a 3-tall
                    // wall, so it proactive-jumps the step and drifts into the wall
                    // MID-AIR. The reported bug: it sometimes sticks to the wall face
                    // and stops falling ("glued", no gravity). Run under real NMS with
                    // -Dsimpleboxer.debug to capture the sim<->server boundary trace.
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 310, 100));
                    int baseX = 310;
                    int baseZ = 100;
                    int groundY = 80; // stone top at y=81 (feet ground)
                    context.syncRun(() -> {
                        for (int x = baseX - 6; x <= baseX + 6; x++) {
                            // 1-block step at z=baseZ+3 (top y=82) — the jump trigger.
                            world.getBlockAt(x, groundY + 1, baseZ + 3).setType(Material.STONE);
                            // 3-tall wall right behind it at z=baseZ+4 (y 81..83).
                            for (int dy = 1; dy <= 3; dy++) {
                                world.getBlockAt(x, groundY + dy, baseZ + 4).setType(Material.STONE);
                            }
                        }
                    });
                    // A stationary post BEYOND the wall so the boxer keeps charging +Z.
                    Boxer post = Arenas.spawn("WallPost",
                            center.clone().add(0, 0, 12), DifficultyPresets.DUMMY);
                    Boxer runner = Arenas.spawn("WallRunner", center,
                            BoxerSettings.DEFAULTS.withCps(0.0));
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> runner.setTarget(post.player()));
                        // Let it reach and start jumping into the wall.
                        context.awaitTicks(30);
                        // Sample feet-Y for the "glued airborne, no gravity" signature:
                        // elevated (well above ground) AND not changing (gravity absent).
                        // TRUE glue = elevated AND not changing AND airborne (no support
                        // under the feet). Standing on the step (onGround) is NOT glue —
                        // that is a nav dead-end, a different problem.
                        double feetGround = groundY + 1.0; // 81.0
                        double prevY = context.sync(() -> runner.player().getLocation().getY());
                        int stillAirborne = 0;
                        int worstStuck = 0;
                        double stuckY = 0.0;
                        for (int tick = 0; tick < 80; tick++) {
                            context.awaitTicks(1);
                            double[] yg = context.sync(() -> new double[] {
                                    runner.player().getLocation().getY(),
                                    runner.player().isOnGround() ? 1.0 : 0.0 });
                            double y = yg[0];
                            boolean onGround = yg[1] > 0.5;
                            boolean elevated = y > feetGround + 0.6;
                            boolean still = Math.abs(y - prevY) < 0.02;
                            if (elevated && still && !onGround) {
                                if (++stillAirborne > worstStuck) {
                                    worstStuck = stillAirborne;
                                    stuckY = y;
                                }
                            } else {
                                stillAirborne = 0;
                            }
                            prevY = y;
                        }
                        // A glued boxer hovers airborne (no support) for many ticks; a
                        // healthy one falls, bounces, or rests on a real surface.
                        context.expect(worstStuck < 8,
                                "boxer did not glue AIRBORNE to the wall (worst floating run "
                                        + worstStuck + " ticks at y=" + stuckY + ")");
                    } finally {
                        context.syncRun(runner::remove);
                        context.syncRun(post::remove);
                    }
                }),

                new TestCase("movement: a boxer knocked up into a bare wall slides down",
                        context -> {
                    // WS1c faithful repro of the reported bug: loft an IDLE boxer UP and
                    // INTO a tall wall with NOTHING to stand on, then watch whether gravity
                    // slides it back down the wall face (vanilla) or it "glues" there. With
                    // -Dsimpleboxer.debug the wallCollide trace logs sim-vs-server Y, so a
                    // stuck ENTITY (sim falls, server frozen) is distinguishable from a stuck
                    // SIM. No step, no ledge — the pure "airborne against a wall" case.
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 340, 100));
                    int baseX = 340;
                    int baseZ = 100;
                    int groundY = 80; // stone top at y=81 (feet ground)
                    context.syncRun(() -> {
                        for (int x = baseX - 6; x <= baseX + 6; x++) {
                            for (int dy = 1; dy <= 4; dy++) {
                                world.getBlockAt(x, groundY + dy, baseZ + 2).setType(Material.STONE);
                            }
                        }
                    });
                    // An idle dummy standing one block in front of the wall.
                    Boxer victim = Arenas.spawn("WallSlider",
                            center.clone().add(0, 0, 1), DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(10); // settle on the ground
                        double feetGround = groundY + 1.0; // 81.0
                        // Loft it UP and INTO the wall — a jump/knock into the wall face.
                        // A boxer captures its own SetEntityMotion, so this reaches the sim.
                        context.syncRun(() -> victim.player().setVelocity(new Vector(0, 0.62, 0.55)));
                        double prevY = context.sync(() -> victim.player().getLocation().getY());
                        int airborneStill = 0;
                        int worstStuck = 0;
                        double stuckY = 0.0;
                        double maxY = prevY;
                        boolean everElevated = false;
                        for (int tick = 0; tick < 60; tick++) {
                            context.awaitTicks(1);
                            double[] yg = context.sync(() -> new double[] {
                                    victim.player().getLocation().getY(),
                                    victim.player().isOnGround() ? 1.0 : 0.0 });
                            double y = yg[0];
                            boolean onGround = yg[1] > 0.5;
                            maxY = Math.max(maxY, y);
                            if (y > feetGround + 0.4) {
                                everElevated = true;
                            }
                            boolean elevated = y > feetGround + 0.4;
                            boolean still = Math.abs(y - prevY) < 0.02;
                            if (elevated && still && !onGround) {
                                if (++airborneStill > worstStuck) {
                                    worstStuck = airborneStill;
                                    stuckY = y;
                                }
                            } else {
                                airborneStill = 0;
                            }
                            prevY = y;
                        }
                        double finalY = context.sync(() -> victim.player().getLocation().getY());
                        boolean finalGround = context.sync(() -> victim.player().isOnGround());
                        context.expect(everElevated,
                                "the knock actually lofted it against the wall (maxY " + maxY + ")");
                        context.expect(worstStuck < 8,
                                "boxer slid DOWN the wall, not glued (worst glued run " + worstStuck
                                        + " ticks at y=" + stuckY + ")");
                        context.expect(finalY < feetGround + 0.3 && finalGround,
                                "ended back on the ground (y=" + finalY + ", onGround=" + finalGround + ")");
                    } finally {
                        context.syncRun(victim::remove);
                    }
                }));
    }
}

package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import me.vexmc.simpleboxer.tester.TestContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The architecture's live proof: a boxer spawns as a real player, walks on
 * real physics, and — the load-bearing assertion — FLIES when the server
 * sends it a velocity packet, because the capture → decode → client-physics
 * → move-packet loop is what carries every knockback it will ever take.
 */
public final class SpawnSuite {

    private SpawnSuite() {}

    public static @NotNull List<TestCase> tests(@NotNull SimpleBoxerPlugin plugin) {
        return List.of(
                new TestCase("spawn: boxer is a live, named, targetable player", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 40, 40));
                    Boxer boxer = Arenas.spawn("SpawnProof", center,
                                    DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        context.expect(boxer.player().isOnline(), "boxer player reports online");
                        context.expect(Bukkit.getPlayerExact("SpawnProof") != null,
                                "getPlayerExact resolves the boxer by name");
                        context.expect(plugin().boxers().isBoxer(boxer.uuid()),
                                "registry recognizes the uuid");
                        context.expect(!boxer.player().isDead(), "boxer is alive");

                        // Vanilla command targeting: teleport it 3 blocks east.
                        Location moved = center.clone().add(3.0, 0.0, 0.0);
                        boolean dispatched = context.sync(() -> Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                "tp SpawnProof " + moved.getX() + " " + moved.getY() + " " + moved.getZ()));
                        context.expect(dispatched, "console /tp accepts the boxer as a target");
                        context.awaitUntil(() -> boxer.player().getLocation()
                                        .distanceSquared(moved) < 1.0,
                                40, "boxer to confirm the teleport and settle there");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("spawn: velocity packet flies the boxer (the architecture proof)", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 70, 40));
                    Boxer boxer = Arenas.spawn("KnockProof", center,
                                    DifficultyPresets.DUMMY);
                    try {
                        // Condition-based settle: a fixed wait races slow
                        // spawn-falls under end-of-matrix load.
                        context.awaitUntil(() -> {
                            try {
                                return boxer.player().isOnGround();
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 60, "the boxer to settle onto the floor");
                        context.awaitTicks(3);
                        double startZ = context.sync(() -> boxer.player().getLocation().getZ());
                        // The era sprint stamp along +Z, applied the way every
                        // plugin applies knockback — setVelocity sends a real
                        // ClientboundSetEntityMotionPacket to the connection.
                        context.syncRun(() -> boxer.player()
                                .setVelocity(new Vector(0.0, 0.4607, 0.9)));
                        context.awaitUntil(() -> {
                            try {
                                double z = boxer.player().getLocation().getZ();
                                return z - startZ > 3.5;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 80, "the boxer to fly the knock trajectory");
                        context.awaitTicks(30); // let it settle
                        double travelled = context.sync(
                                () -> boxer.player().getLocation().getZ()) - startZ;
                        // The emulator pins 4.948 on ideal flat ground; the live
                        // server adds spawn-jitter and packet-grain — accept a
                        // generous era-shaped band, reject the broken extremes
                        // (no flight at all, or physics-less sliding).
                        context.expectNear(4.95, travelled, 0.8, "settled knock distance");
                        context.expect(context.sync(() -> boxer.player().isOnGround()),
                                "boxer landed back on the floor");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("spawn: boxer follows and reaches its target", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 100, 40));
                    // The walker chases a dummy boxer 8 blocks north.
                    Boxer post = Arenas.spawn("GoalPost", center.clone().add(0, 0, 8),
                                    DifficultyPresets.DUMMY);
                    Boxer walker = Arenas.spawn("Walker", center,
                                    BoxerSettings.DEFAULTS);
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> walker.setTarget(post.player()));
                        context.awaitUntil(() -> {
                            try {
                                return walker.player().getLocation()
                                        .distance(post.player().getLocation()) < 3.2;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "walker to sprint to its target");
                        context.expect(walker.player().getLocation().distance(
                                        post.player().getLocation()) > 1.5,
                                "walker holds stop distance instead of body-blocking");
                    } finally {
                        context.syncRun(walker::remove);
                        context.syncRun(post::remove);
                    }
                }),

                new TestCase("spawn: pause freezes the brain, resume revives it", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 130, 40));
                    Boxer post = Arenas.spawn("PausePost", center.clone().add(0, 0, 8),
                                    DifficultyPresets.DUMMY);
                    Boxer pacer = Arenas.spawn("Pacer", center,
                                    BoxerSettings.DEFAULTS);
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() -> {
                            pacer.pause();
                            pacer.setTarget(post.player());
                        });
                        Location before = context.sync(() -> pacer.player().getLocation());
                        context.awaitTicks(30);
                        Location after = context.sync(() -> pacer.player().getLocation());
                        context.expect(before.distanceSquared(after) < 0.01,
                                "paused boxer holds position");
                        context.syncRun(pacer::resume);
                        context.awaitUntil(() -> {
                            try {
                                return pacer.player().getLocation()
                                        .distance(post.player().getLocation()) < 3.2;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "resumed boxer to reach the target");
                    } finally {
                        context.syncRun(pacer::remove);
                        context.syncRun(post::remove);
                    }
                }),

                new TestCase("spawn: removal cleans the player list", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 40, 70));
                    Boxer boxer = Arenas.spawn("Ephemeral", center,
                                    DifficultyPresets.DUMMY);
                    context.awaitTicks(3);
                    context.syncRun(boxer::remove);
                    context.awaitTicks(3);
                    context.expect(Bukkit.getPlayerExact("Ephemeral") == null,
                            "removed boxer leaves the player list");
                    context.expect(plugin().boxers().byName("Ephemeral").isEmpty(),
                            "registry forgets the boxer");
                }));
    }

    private static SimpleBoxerPlugin plugin() {
        return (SimpleBoxerPlugin) Bukkit.getPluginManager().getPlugin("SimpleBoxer");
    }
}

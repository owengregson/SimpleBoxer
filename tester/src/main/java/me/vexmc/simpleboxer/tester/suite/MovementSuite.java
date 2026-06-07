package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
                    Boxer plain = Arenas.spawn("PlainRacer",
                            center.clone().add(-6, 0, 0), BoxerSettings.DEFAULTS);
                    Boxer speedy = Arenas.spawn("SpeedyRacer",
                            center.clone().add(6, 0, 0), BoxerSettings.DEFAULTS);
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
                    Boxer presser = Arenas.spawn("Presser", center, BoxerSettings.DEFAULTS);
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
                        // 30 consecutive in-pocket ticks with the sprint
                        // flag held. The server flag is fed ONLY by the
                        // boxer's own PlayerCommand packets, so a single
                        // forward release (the old stop-ring bug) — or any
                        // toggle flapping — shows here immediately.
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
                }));
    }
}

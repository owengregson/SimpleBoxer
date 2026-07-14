package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The player-likeness guards: invincibility that never alters the hit
 * itself, instant in-place respawn on a one-shot, pinned hunger, and a real
 * mortal when invincibility is switched off.
 */
public final class GuardSuite {

    private GuardSuite() {}

    public static @NotNull List<TestCase> tests() {
        return List.of(
                new TestCase("guard: damage lands fully, health restores next tick", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 40, 100));
                    Boxer boxer = Arenas.spawn("Tank", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        double max = context.sync(() -> boxer.player().getHealth());
                        context.syncRun(() -> boxer.player().damage(6.0));
                        // The hit registered (immunity window armed)...
                        int immunity = context.sync(() -> boxer.player().getNoDamageTicks());
                        context.expect(immunity > 0, "the hit armed the immunity window");
                        // ...and the health comes back.
                        context.awaitTicks(3);
                        double after = context.sync(() -> boxer.player().getHealth());
                        context.expectNear(max, after, 0.01, "restored health");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("guard: a one-shot burst is survived in place, not fatal", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 70, 100));
                    Boxer boxer = Arenas.spawn("Lazarus", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        // The fixed invincibility caps an otherwise-lethal hit so the
                        // boxer never dies (the old restore-next-tick let it die first).
                        context.syncRun(() -> boxer.player().damage(1000.0));
                        context.awaitUntil(() -> {
                            try {
                                return !boxer.player().isDead()
                                        && boxer.player().getHealth() > 19.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 40, "the boxer to survive the burst at full health");
                        double distance = context.sync(() ->
                                boxer.player().getLocation().distance(center));
                        context.expect(distance < 3.0,
                                "survived in place (" + distance + " blocks away)");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("guard: hunger is pinned full", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 100, 100));
                    Boxer boxer = Arenas.spawn("Sated", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(3);
                        boolean cancelled = context.sync(() -> {
                            FoodLevelChangeEvent event =
                                    new FoodLevelChangeEvent(boxer.player(), 3);
                            Bukkit.getPluginManager().callEvent(event);
                            return event.isCancelled();
                        });
                        context.expect(cancelled, "food-level drops are cancelled");
                        context.expect(context.sync(() -> boxer.player().getFoodLevel()) == 20,
                                "food sits at 20");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("guard: invincibility off makes a mortal", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 130, 100));
                    Boxer boxer = Arenas.spawn("Mortal", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        context.syncRun(() ->
                                boxer.retune(boxer.settings().withInvincible(false)));
                        double before = context.sync(() -> boxer.player().getHealth());
                        context.syncRun(() -> boxer.player().damage(6.0));
                        context.awaitTicks(3);
                        double after = context.sync(() -> boxer.player().getHealth());
                        context.expect(after < before - 4.0,
                                "health dropped for real (" + before + " → " + after + ")");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }));
    }
}

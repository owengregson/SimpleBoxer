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
 * Proper invincibility and the unified external-velocity capture. A burst larger
 * than current health must NOT kill (the historical bug) yet the boxer must still
 * take the knockback — and a server-side {@code setVelocity} (StarEnchants-style)
 * must move the clientless boxer, which it previously did not on modern Paper.
 */
public final class InvincibilitySuite {

    private InvincibilitySuite() {}

    public static @NotNull List<TestCase> tests() {
        return List.of(
                new TestCase("invincible: a lethal burst does not kill and health holds", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 160, 160));
                    Boxer boxer = Arenas.spawn("Unkillable", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        // A one-tick burst far exceeding current health.
                        context.syncRun(() -> boxer.player().damage(100000.0));
                        context.awaitTicks(3);
                        boolean alive = context.sync(() ->
                                !boxer.player().isDead() && boxer.player().getHealth() > 19.0);
                        context.expect(alive, "the boxer survived the burst at full health");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("invincible: still takes a server-side velocity push", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 190, 160));
                    // DUMMY stands still (STAND), so any displacement is the push.
                    Boxer boxer = Arenas.spawn("Pushable", center, DifficultyPresets.DUMMY);
                    try {
                        context.awaitTicks(5);
                        Location before = context.sync(() -> boxer.player().getLocation().clone());
                        // A plugin-applied velocity (StarEnchants / Mental style) fires
                        // PlayerVelocityEvent, which the resolver must integrate.
                        context.syncRun(() -> boxer.player().setVelocity(new Vector(0.8, 0.2, 0.0)));
                        context.awaitTicks(12);
                        double moved = context.sync(() ->
                                boxer.player().getLocation().distance(before));
                        context.expect(moved > 0.8,
                                "the server-side velocity moved the boxer (" + moved + " blocks)");
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }));
    }
}

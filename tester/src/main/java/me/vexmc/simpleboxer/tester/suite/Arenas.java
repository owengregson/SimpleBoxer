package me.vexmc.simpleboxer.tester.suite;

import java.util.concurrent.TimeUnit;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerSpawnRequest;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/** Shared staging for the suites: flat stone pads and driver-thread spawns. */
final class Arenas {

    private Arenas() {}

    /** A flat stone pad with clear headroom; returns its center. */
    static @NotNull Location arena(@NotNull World world, int baseX, int baseZ) {
        int y = 80;
        for (int x = baseX - 10; x <= baseX + 10; x++) {
            for (int z = baseZ - 10; z <= baseZ + 18; z++) {
                world.getBlockAt(x, y, z).setType(Material.STONE);
                for (int clear = 1; clear <= 4; clear++) {
                    world.getBlockAt(x, y + clear, z).setType(Material.AIR);
                }
            }
        }
        return new Location(world, baseX + 0.5, y + 1.0, baseZ + 0.5);
    }

    /**
     * Driver-thread spawn: the future completes on the main thread, so this
     * must never be called from inside {@code context.sync}.
     */
    static @NotNull Boxer spawn(@NotNull String name, @NotNull Location location,
            @NotNull BoxerSettings settings) throws Exception {
        return plugin().boxers()
                .spawn(new BoxerSpawnRequest(name, location, settings, null, null))
                .get(10, TimeUnit.SECONDS);
    }

    static @NotNull SimpleBoxerPlugin plugin() {
        return (SimpleBoxerPlugin) Bukkit.getPluginManager().getPlugin("SimpleBoxer");
    }
}

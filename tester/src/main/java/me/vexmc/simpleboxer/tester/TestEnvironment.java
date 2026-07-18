package me.vexmc.simpleboxer.tester;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Locks the live server into a hermetic environment before the suite runs:
 * no hostile mobs, frozen noon, clear weather. The suites stage MORTAL
 * boxers on floating pads, and a stray creeper or skeleton can kill one
 * mid-case, turning a behavioral assertion into a flake.
 *
 * <p>Seen live on 1.18.2 (and only there): its flat-world ground sits at
 * y=-60, more than 128 blocks below the y=80 pads, so the pads are the only
 * spawnable surfaces inside the boxers' spawn sphere — every natural spawn
 * lands next to a boxer, is saved with the world, and greets the next run
 * ("SweatPot was blown up by Creeper", "Chugger was shot by Skeleton"). On
 * 1.17.1 the flat ground at y≈4 is in range and soaks the mob cap 77 blocks
 * below the pads, which is why the same suite never flaked there.
 *
 * <p>Three layers, because each has a hole on its own: gamerules stop new
 * natural spawns, the {@link EntitiesLoadEvent} sweep removes hostiles that
 * ride in with lazily-loaded chunks from a previous run's save, and the
 * {@link CreatureSpawnEvent} net catches any spawn path no gamerule covers.
 * Gamerules resolve by name so a server era missing one skips it instead of
 * throwing.
 */
final class TestEnvironment implements Listener {

    /** Every vanilla path that can put a hostile mob into the world. */
    private static final String[] SPAWN_RULES = {
            "doMobSpawning", "doPatrolSpawning", "doTraderSpawning", "doInsomnia",
            "doDaylightCycle", "doWeatherCycle",
    };

    private final JavaPlugin plugin;

    private TestEnvironment(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static void impose(@NotNull JavaPlugin plugin) {
        TestEnvironment environment = new TestEnvironment(plugin);
        int purged = 0;
        for (World world : Bukkit.getWorlds()) {
            for (String rule : SPAWN_RULES) {
                off(world, rule);
            }
            // Cosmetic stabilizers only — the embargo layers above/below carry the
            // hermetic guarantee. 26.x preview worlds without a world clock THROW
            // on setTime ("Cannot set time in world without world clock"), and a
            // fixed-weather world may reject weather writes; neither may take the
            // tester down with it.
            try {
                world.setTime(6_000L); // noon, and doDaylightCycle=false pins it
            } catch (RuntimeException clocklessWorld) {
                // leave the world's native time source alone
            }
            try {
                world.setStorm(false);
                world.setThundering(false);
            } catch (RuntimeException fixedWeather) {
                // leave the weather state alone
            }
            for (Entity entity : world.getEntities()) {
                if (hostile(entity)) {
                    entity.remove();
                    purged++;
                }
            }
        }
        Bukkit.getPluginManager().registerEvents(environment, plugin);
        plugin.getLogger().info("Hermetic environment: hostile spawning off, noon frozen"
                + (purged > 0 ? ", purged " + purged + " leftover hostile mob(s)" : ""));
    }

    /**
     * Hostiles saved by a previous run load with their chunk long after the
     * boot sweep — the arenas sit in chunks nothing loads until a boxer
     * spawns there.
     */
    @EventHandler
    public void onEntitiesLoad(@NotNull EntitiesLoadEvent event) {
        int purged = 0;
        for (Entity entity : event.getEntities()) {
            if (hostile(entity)) {
                entity.remove();
                purged++;
            }
        }
        if (purged > 0) {
            plugin.getLogger().info("Hermetic environment: purged " + purged
                    + " saved hostile mob(s) from a loading chunk");
        }
    }

    /**
     * The net behind the gamerules. CUSTOM stays open so a future suite can
     * still stage a mob on purpose.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && hostile(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private static void off(@NotNull World world, @NotNull String name) {
        GameRule<?> rule = GameRule.getByName(name);
        if (rule != null && rule.getType() == Boolean.class) {
            @SuppressWarnings("unchecked")
            GameRule<Boolean> toggle = (GameRule<Boolean>) rule;
            world.setGameRule(toggle, Boolean.FALSE);
        }
    }

    /**
     * Everything a test world can produce that attacks unprovoked: Monster
     * covers the raider/zombie/skeleton/creeper families; Slime and Phantom
     * are hostile without implementing Monster on the 1.17 API floor.
     */
    private static boolean hostile(@NotNull Entity entity) {
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom;
    }
}

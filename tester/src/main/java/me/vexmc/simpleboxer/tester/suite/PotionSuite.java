package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The splash-pot self-heal, proven on a live server: a mortal sweat-preset
 * boxer seeds its finite supply (overflowing past the hotbar), throws REAL
 * ThrownPotions when low, recovers, and keeps throwing far past Spigot's
 * 9-use-item lifetime spam grace — the regression pin for the use-item
 * timestamp stamp (an unstamped boxer's connection is silently muted after
 * exactly 9 use-item packets, so double-digit launches are unreachable).
 */
public final class PotionSuite {

    private PotionSuite() {}

    /** Counts server-confirmed ThrownPotion launches by one shooter. */
    private static final class PotCounter implements Listener {
        final AtomicInteger launches = new AtomicInteger();
        private final UUID shooter;

        PotCounter(UUID shooter) {
            this.shooter = shooter;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onLaunch(ProjectileLaunchEvent event) {
            if (event.getEntity() instanceof ThrownPotion potion
                    && potion.getShooter() instanceof Player thrower
                    && thrower.getUniqueId().equals(shooter)) {
                launches.incrementAndGet();
            }
        }
    }

    public static @NotNull List<TestCase> tests(@NotNull Plugin testerPlugin) {
        return List.of(
                new TestCase("potions: a low sweat boxer splashes real pots and recovers", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 400, 40));
                    Boxer bag = Arenas.spawn("PotBag", center.clone().add(0, 0, 4),
                            DifficultyPresets.DUMMY);
                    Boxer healer = Arenas.spawn("SweatPot", center, DifficultyPresets.SWEAT);
                    PotCounter counter = new PotCounter(healer.uuid());
                    context.syncRun(() -> Bukkit.getPluginManager()
                            .registerEvents(counter, testerPlugin));
                    try {
                        // A KITLESS sweat boxer still seeds its supply (the
                        // consumables seam, not the loadout one), and count 6
                        // overflows the 6th pot past the reserved hotbar slots
                        // into main-inventory slot 9: pots at 2,5,6,7,8,9.
                        context.awaitUntil(() -> isPot(item(healer, 2)), 40,
                                "the pot slot to seed on a kitless boxer");
                        context.syncRun(() -> {
                            context.expect(isPot(item(healer, 9)),
                                    "the 6th pot overflowed into main-inventory slot 9");
                            context.expect(!isPot(item(healer, 10)),
                                    "seeding stops at the configured count");
                        });

                        context.syncRun(() -> healer.setTarget(bag.player()));
                        context.awaitTicks(20); // let the engage settle

                        // Kill natural regen so recovery can ONLY come from the
                        // potions: no saturation, food below the regen line (18).
                        context.syncRun(() -> {
                            healer.player().setSaturation(0.0f);
                            healer.player().setFoodLevel(17);
                            healer.player().setHealth(4.0); // below the 8 HP trigger
                        });

                        context.awaitUntil(() -> counter.launches.get() >= 2, 600,
                                "at least two real ThrownPotion launches");
                        context.awaitUntil(() -> health(healer) >= 17.9, 600,
                                "recovery to the 18 HP resume line (Instant Health II payload)");
                        context.expect(context.sync(() -> isPot(item(healer, 2))),
                                "the pot slot restocked from the reserve");
                        context.note("launches: " + counter.launches.get()
                                + ", health: " + health(healer));
                    } finally {
                        context.syncRun(() -> HandlerList.unregisterAll(counter));
                        context.syncRun(healer::remove);
                        context.syncRun(bag::remove);
                    }
                }),

                new TestCase("potions: the pot budget outlives Spigot's 9-use-item grace", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 430, 40));
                    Boxer bag = Arenas.spawn("PotBag2", center.clone().add(0, 0, 4),
                            DifficultyPresets.DUMMY);
                    // A deep reserve (20 > one hotbar) and a huge cap: reaching
                    // double-digit throws REQUIRES both the timestamp stamp (the
                    // unstamped connection is muted after 9 lifetime use-items —
                    // blockhit taps included) and main-inventory restocking.
                    BoxerSettings deepPockets = DifficultyPresets.SWEAT
                            .withSelfHeal(new BoxerSettings.SelfHeal(true, 8.0, 18.0, 36))
                            .withItems(new BoxerSettings.Items(
                                    true, false, 0, 1, 2, 3, 4, false, true, 20));
                    Boxer healer = Arenas.spawn("Chugger", center, deepPockets);
                    PotCounter counter = new PotCounter(healer.uuid());
                    context.syncRun(() -> Bukkit.getPluginManager()
                            .registerEvents(counter, testerPlugin));
                    try {
                        // 20 pots: hotbar 2,5,6,7,8 (weapon/rod/food/block slots
                        // reserved) + main inventory 9..23, and not one more.
                        context.awaitUntil(() -> isPot(item(healer, 2)), 40,
                                "the deep reserve to seed");
                        context.syncRun(() -> {
                            context.expect(isPot(item(healer, 23)),
                                    "the 20th pot landed in main-inventory slot 23");
                            context.expect(!isPot(item(healer, 24)),
                                    "seeding stops at the configured count");
                        });

                        context.syncRun(() -> healer.setTarget(bag.player()));
                        context.awaitTicks(20);

                        for (int round = 1; round <= 8 && counter.launches.get() < 10; round++) {
                            final int before = counter.launches.get();
                            final String label = "round " + round;
                            context.syncRun(() -> {
                                healer.player().setSaturation(0.0f);
                                healer.player().setFoodLevel(17);
                                healer.player().setHealth(4.0);
                            });
                            context.awaitUntil(() -> counter.launches.get() >= before + 2, 500,
                                    "two more pot launches in " + label);
                            context.awaitUntil(() -> health(healer) >= 17.9, 500,
                                    "recovery in " + label);
                        }

                        int launches = counter.launches.get();
                        context.expect(launches >= 10,
                                "double-digit lifetime launches prove the anti-spam timestamp"
                                        + " stamp and the main-inventory restock (got "
                                        + launches + ")");
                        context.note("lifetime pot launches: " + launches);
                    } finally {
                        context.syncRun(() -> HandlerList.unregisterAll(counter));
                        context.syncRun(healer::remove);
                        context.syncRun(bag::remove);
                    }
                }));
    }

    private static ItemStack item(@NotNull Boxer boxer, int slot) {
        try {
            return boxer.player().getInventory().getItem(slot);
        } catch (Throwable gone) {
            return null;
        }
    }

    private static boolean isPot(ItemStack item) {
        return item != null && item.getType() == Material.SPLASH_POTION;
    }

    private static double health(@NotNull Boxer boxer) {
        try {
            return boxer.player().getHealth();
        } catch (Throwable gone) {
            return 0.0;
        }
    }
}

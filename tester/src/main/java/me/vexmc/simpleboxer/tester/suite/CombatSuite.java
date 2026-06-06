package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Boxers fight: the aimbot lands real melee hits through the real packet
 * path (its ATTACK dispatches through its own game listener into vanilla's
 * handler), vanilla immunity gates the rate, w-tap shows up as the sprint
 * toggle rhythm, and a dummy never throws a punch.
 */
public final class CombatSuite {

    private CombatSuite() {}

    private static final class HitCounter implements Listener {
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger sprintToggles = new AtomicInteger();
        private final UUID attacker;

        HitCounter(UUID attacker) {
            this.attacker = attacker;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent event) {
            if (event.getDamager() instanceof Player damager
                    && damager.getUniqueId().equals(attacker)) {
                hits.incrementAndGet();
            }
        }

        @EventHandler
        public void onSprint(PlayerToggleSprintEvent event) {
            if (event.getPlayer().getUniqueId().equals(attacker)) {
                sprintToggles.incrementAndGet();
            }
        }
    }

    public static @NotNull List<TestCase> tests(@NotNull Plugin testerPlugin) {
        return List.of(
                new TestCase("combat: aimbot lands gated hits on a dummy", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 160, 40));
                    Boxer dummy = Arenas.spawn("Bag", center.clone().add(0, 0, 4),
                            DifficultyPresets.DUMMY);
                    Boxer fighter = Arenas.spawn("Slugger", center, DifficultyPresets.AIMBOT);
                    HitCounter counter = new HitCounter(fighter.uuid());
                    context.syncRun(() -> Bukkit.getPluginManager()
                            .registerEvents(counter, testerPlugin));
                    try {
                        context.syncRun(() -> fighter.setTarget(dummy.player()));
                        context.awaitTicks(120); // six seconds of swinging
                        int hits = counter.hits.get();
                        // Vanilla immunity admits ~2 hits/s; combat plugins
                        // (OCM-style hit delay) admit ~1/s. Anything below
                        // says the attack path is broken; anything way above
                        // says immunity is being bypassed.
                        context.expect(hits >= 5, "at least 5 hits in 6s (got " + hits + ")");
                        context.expect(hits <= 16, "immunity gates the spam (got " + hits + ")");
                        context.note("hits in 120 ticks: " + hits);
                    } finally {
                        context.syncRun(() -> HandlerList.unregisterAll(counter));
                        context.syncRun(fighter::remove);
                        context.syncRun(dummy::remove);
                    }
                }),

                new TestCase("combat: hits actually knock the victim", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 190, 40));
                    Boxer dummy = Arenas.spawn("Knockee", center.clone().add(0, 0, 4),
                            DifficultyPresets.DUMMY);
                    Boxer fighter = Arenas.spawn("Knocker", center, DifficultyPresets.AIMBOT);
                    try {
                        double startZ = context.sync(() -> dummy.player().getLocation().getZ());
                        context.syncRun(() -> fighter.setTarget(dummy.player()));
                        context.awaitUntil(() -> {
                            try {
                                return dummy.player().getLocation().getZ() - startZ > 1.0;
                            } catch (Throwable gone) {
                                return false;
                            }
                        }, 100, "the dummy to be knocked away");
                    } finally {
                        context.syncRun(fighter::remove);
                        context.syncRun(dummy::remove);
                    }
                }),

                new TestCase("combat: w-tap shows as the sprint toggle rhythm", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 220, 40));
                    Boxer dummy = Arenas.spawn("TapBag", center.clone().add(0, 0, 5),
                            DifficultyPresets.DUMMY);
                    Boxer tapper = Arenas.spawn("Tapper", center, DifficultyPresets.HARD);
                    HitCounter counter = new HitCounter(tapper.uuid());
                    context.syncRun(() -> Bukkit.getPluginManager()
                            .registerEvents(counter, testerPlugin));
                    try {
                        context.syncRun(() -> tapper.setTarget(dummy.player()));
                        context.awaitTicks(120);
                        context.expect(counter.hits.get() >= 3,
                                "the w-tapper still lands hits (got " + counter.hits.get() + ")");
                        // Each landed hit drops + re-arms sprint: ≥2 toggles
                        // per cycle beyond the initial sprint start.
                        context.expect(counter.sprintToggles.get() >= 4,
                                "w-tap toggles sprint repeatedly (got "
                                        + counter.sprintToggles.get() + ")");
                        context.note("hits " + counter.hits.get()
                                + ", sprint toggles " + counter.sprintToggles.get());
                    } finally {
                        context.syncRun(() -> HandlerList.unregisterAll(counter));
                        context.syncRun(tapper::remove);
                        context.syncRun(dummy::remove);
                    }
                }),

                new TestCase("combat: a dummy never throws a punch", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 250, 40));
                    Boxer bag = Arenas.spawn("Pacifist", center, DifficultyPresets.DUMMY);
                    Boxer victim = Arenas.spawn("Bystander", center.clone().add(0, 0, 2),
                            DifficultyPresets.DUMMY);
                    HitCounter counter = new HitCounter(bag.uuid());
                    context.syncRun(() -> Bukkit.getPluginManager()
                            .registerEvents(counter, testerPlugin));
                    try {
                        context.syncRun(() -> bag.setTarget(victim.player()));
                        context.awaitTicks(60);
                        context.expect(counter.hits.get() == 0,
                                "zero CPS means zero hits (got " + counter.hits.get() + ")");
                    } finally {
                        context.syncRun(() -> HandlerList.unregisterAll(counter));
                        context.syncRun(bag::remove);
                        context.syncRun(victim::remove);
                    }
                }));
    }
}

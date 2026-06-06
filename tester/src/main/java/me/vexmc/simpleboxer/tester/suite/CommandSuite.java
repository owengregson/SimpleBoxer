package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import me.vexmc.simpleboxer.tester.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.jetbrains.annotations.NotNull;

/** The /boxer tree drives the whole lifecycle, console-side. */
public final class CommandSuite {

    private CommandSuite() {}

    private static boolean dispatch(String commandLine) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
    }

    public static @NotNull List<TestCase> tests(@NotNull SimpleBoxerPlugin plugin) {
        return List.of(
                new TestCase("command: spawn, tune, pause, remove round-trip", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 280, 40));
                    String at = " at " + center.getX() + " " + center.getY() + " " + center.getZ();
                    try {
                        context.syncRun(() -> context.expect(
                                dispatch("boxer spawn CmdBot hard" + at),
                                "spawn dispatches"));
                        context.awaitUntil(() -> plugin.boxers().byName("CmdBot").isPresent(),
                                40, "the command-spawned boxer to register");
                        Boxer boxer = plugin.boxers().byName("CmdBot").orElseThrow();
                        context.expect(boxer.settings().cps() == 10.0,
                                "spawned with the hard preset (cps 10)");

                        context.syncRun(() -> dispatch("boxer set CmdBot ping 250"));
                        context.expect(boxer.settings().pingMs() == 250, "ping retuned to 250");

                        context.syncRun(() -> dispatch("boxer set CmdBot cps 4.5"));
                        context.expect(boxer.settings().cps() == 4.5, "cps retuned to 4.5");

                        context.syncRun(() -> dispatch("boxer pause CmdBot"));
                        context.expect(boxer.paused(), "paused via command");
                        context.syncRun(() -> dispatch("boxer resume CmdBot"));
                        context.expect(!boxer.paused(), "resumed via command");

                        context.syncRun(() -> dispatch("boxer list"));
                        context.syncRun(() -> dispatch("boxer info CmdBot"));
                    } finally {
                        context.syncRun(() -> dispatch("boxer remove CmdBot"));
                    }
                    context.awaitUntil(() -> plugin.boxers().byName("CmdBot").isEmpty(),
                            20, "the boxer to be removed via command");
                }),

                new TestCase("command: tab completion offers boxer names and presets", context -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location center = context.sync(() -> Arenas.arena(world, 310, 40));
                    Boxer boxer = Arenas.spawn("Completable", center, DifficultyPresets.DUMMY);
                    try {
                        PluginCommand command = plugin.getCommand("boxer");
                        context.expect(command != null, "the /boxer command is registered");
                        List<String> subs = context.sync(() -> command.tabComplete(
                                Bukkit.getConsoleSender(), "boxer", new String[] {""}));
                        context.expect(subs.contains("spawn") && subs.contains("set"),
                                "first arg completes subcommands");
                        List<String> names = context.sync(() -> command.tabComplete(
                                Bukkit.getConsoleSender(), "boxer",
                                new String[] {"info", "Comp"}));
                        context.expect(names.contains("Completable"),
                                "boxer names complete: " + names);
                        List<String> presets = context.sync(() -> command.tabComplete(
                                Bukkit.getConsoleSender(), "boxer",
                                new String[] {"spawn", "X", "ha"}));
                        context.expect(presets.contains("hard"),
                                "presets complete: " + presets);
                    } finally {
                        context.syncRun(boxer::remove);
                    }
                }),

                new TestCase("command: reload keeps the world turning", context -> {
                    context.syncRun(() -> context.expect(dispatch("boxer reload"),
                            "reload dispatches"));
                }));
    }
}

package me.vexmc.simpleboxer.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerSpawnRequest;
import me.vexmc.simpleboxer.boxer.BoxerManager;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsParser;
import me.vexmc.simpleboxer.config.ConfigStore;
import me.vexmc.simpleboxer.gui.Gui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The /boxer tree. The plugin is GUI-first — bare {@code /boxer} (and
 * {@code /boxer menu}) opens the menu for a player — but the full command tree
 * stays for console, scripting and the integration suite. Op-gated per subtree
 * by plugin.yml permissions; every subcommand answers in plain prose and never
 * throws at the sender.
 */
public final class BoxerCommands implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "menu", "spawn", "remove", "list", "target", "pause", "resume", "set", "info", "reload");
    private static final List<String> SET_KEYS = List.of(
            "ping", "cps", "reach", "aim", "wtap", "preset", "movement", "invincible");

    private final BoxerManager manager;
    private final ConfigStore config;
    private final Gui gui;

    public BoxerCommands(@NotNull BoxerManager manager, @NotNull ConfigStore config,
            @NotNull Gui gui) {
        this.manager = manager;
        this.config = config;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            // Front door: a player gets the GUI; the console gets the help text
            // (it cannot open an inventory).
            if (sender instanceof Player player && player.hasPermission("simpleboxer.gui")) {
                gui.openMain(player);
            } else {
                help(sender, label);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui" -> openMenu(sender);
            case "spawn" -> spawn(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "target" -> target(sender, args);
            case "pause" -> pauseResume(sender, args, true);
            case "resume" -> pauseResume(sender, args, false);
            case "set" -> set(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage("§cUnknown subcommand '" + sub + "'. Try /" + label + ".");
        }
        return true;
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThe menu is in-game only — use the subcommands from console.");
            return;
        }
        if (!player.hasPermission("simpleboxer.gui")) {
            player.sendMessage("§cYou lack simpleboxer.gui.");
            return;
        }
        gui.openMain(player);
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§6SimpleBoxer §7— virtual sparring players");
        sender.sendMessage("§e/" + label + " §7— open the menu (spawn, tune, kit, everything)");
        sender.sendMessage("§7Or drive it from the console / scripts:");
        sender.sendMessage("§e/" + label + " spawn <name> [preset] [skin:<player>] [target:<player>] [at <x> <y> <z>]");
        sender.sendMessage("§e/" + label + " remove <name|all> §7• §e list §7• §e info <name>");
        sender.sendMessage("§e/" + label + " target <name> <player|none> §7• §e pause|resume <name|all>");
        sender.sendMessage("§e/" + label + " set <name> <" + String.join("|", SET_KEYS) + "> <value>");
        sender.sendMessage("§e/" + label + " reload");
        sender.sendMessage("§7Presets: §f" + String.join(", ", config.snapshot().presetNames()));
    }

    /* ------------------------------------------------------------------ */

    private void spawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleboxer.command.spawn")) {
            sender.sendMessage("§cYou lack simpleboxer.command.spawn.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /boxer spawn <name> [preset] [skin:<player>] [target:<player>] [at <x> <y> <z>]");
            return;
        }
        String name = args[1];
        BoxerSettings settings = config.snapshot().defaults();
        String skinOwner = null;
        String targetName = null;
        Location location = sender instanceof Player player ? player.getLocation() : null;

        for (int i = 2; i < args.length; i++) {
            String argument = args[i];
            String lower = argument.toLowerCase(Locale.ROOT);
            if (lower.startsWith("skin:")) {
                skinOwner = argument.substring("skin:".length());
            } else if (lower.startsWith("target:")) {
                targetName = argument.substring("target:".length());
            } else if (lower.equals("at")) {
                if (i + 3 >= args.length) {
                    sender.sendMessage("§c'at' needs three coordinates: at <x> <y> <z>.");
                    return;
                }
                try {
                    location = new Location(
                            sender instanceof Player player ? player.getWorld()
                                    : Bukkit.getWorlds().get(0),
                            Double.parseDouble(args[i + 1]),
                            Double.parseDouble(args[i + 2]),
                            Double.parseDouble(args[i + 3]));
                    i += 3;
                } catch (NumberFormatException bad) {
                    sender.sendMessage("§cCoordinates after 'at' must be numbers.");
                    return;
                }
            } else {
                BoxerSettings preset = config.snapshot().preset(lower);
                if (preset == null) {
                    sender.sendMessage("§cUnknown preset '" + argument + "'. Known: "
                            + String.join(", ", config.snapshot().presetNames()));
                    return;
                }
                settings = preset;
            }
        }
        if (location == null) {
            sender.sendMessage("§cConsole spawns need 'at <x> <y> <z>'.");
            return;
        }

        BoxerSpawnRequest request;
        try {
            request = new BoxerSpawnRequest(name, location, settings, skinOwner, targetName);
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage("§c" + invalid.getMessage());
            return;
        }
        manager.spawn(request).whenComplete((boxer, failure) -> {
            if (failure != null) {
                sender.sendMessage("§cSpawn failed: " + rootMessage(failure));
            } else {
                sender.sendMessage("§aSpawned boxer §f" + boxer.name() + "§a.");
            }
        });
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleboxer.command.spawn")) {
            sender.sendMessage("§cYou lack simpleboxer.command.spawn.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /boxer remove <name|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = manager.all().size();
            manager.removeAll();
            sender.sendMessage("§aRemoved " + count + " boxer(s).");
            return;
        }
        resolve(sender, args[1]).ifPresent(boxer -> {
            boxer.remove();
            sender.sendMessage("§aRemoved boxer §f" + boxer.name() + "§a.");
        });
    }

    private void list(CommandSender sender) {
        var all = manager.all();
        if (all.isEmpty()) {
            sender.sendMessage("§7No boxers are live.");
            return;
        }
        sender.sendMessage("§6Boxers (" + all.size() + "):");
        for (Boxer boxer : all) {
            String target = boxer.target().map(Player::getName).orElse("—");
            sender.sendMessage("§f " + boxer.name()
                    + (boxer.paused() ? " §7[paused]" : "")
                    + " §7→ §f" + target
                    + " §7(ping " + boxer.settings().pingMs()
                    + "ms, cps " + boxer.settings().cps() + ")");
        }
    }

    private void target(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleboxer.command.control")) {
            sender.sendMessage("§cYou lack simpleboxer.command.control.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /boxer target <name> <player|none>");
            return;
        }
        resolve(sender, args[1]).ifPresent(boxer -> {
            if (args[2].equalsIgnoreCase("none")) {
                boxer.setTarget(null);
                sender.sendMessage("§aCleared §f" + boxer.name() + "§a's target.");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§cNo online player named '" + args[2] + "'.");
                return;
            }
            boxer.setTarget(target);
            sender.sendMessage("§a" + boxer.name() + " now hunts §f" + target.getName() + "§a.");
        });
    }

    private void pauseResume(CommandSender sender, String[] args, boolean pause) {
        if (!sender.hasPermission("simpleboxer.command.control")) {
            sender.sendMessage("§cYou lack simpleboxer.command.control.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /boxer " + (pause ? "pause" : "resume") + " <name|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            manager.all().forEach(boxer -> {
                if (pause) {
                    boxer.pause();
                } else {
                    boxer.resume();
                }
            });
            sender.sendMessage("§a" + (pause ? "Paused" : "Resumed") + " every boxer.");
            return;
        }
        resolve(sender, args[1]).ifPresent(boxer -> {
            if (pause) {
                boxer.pause();
            } else {
                boxer.resume();
            }
            sender.sendMessage("§a" + (pause ? "Paused" : "Resumed") + " §f" + boxer.name() + "§a.");
        });
    }

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleboxer.command.tune")) {
            sender.sendMessage("§cYou lack simpleboxer.command.tune.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /boxer set <name> <" + String.join("|", SET_KEYS) + "> <value>");
            return;
        }
        Optional<Boxer> resolved = resolve(sender, args[1]);
        if (resolved.isEmpty()) {
            return;
        }
        Boxer boxer = resolved.get();
        String key = args[2].toLowerCase(Locale.ROOT);
        String value = args[3];
        try {
            BoxerSettings updated = switch (key) {
                case "ping" -> boxer.settings().withPingMs(parseInt("ping", value));
                case "cps" -> boxer.settings().withCps(parseDouble("cps", value));
                case "reach" -> boxer.settings().withReach(parseDouble("reach", value));
                case "aim" -> {
                    AimParams aim = BoxerSettingsParser.aimPresetByName(value);
                    if (aim == null) {
                        throw new IllegalArgumentException(
                                "aim presets: locked, sharp, smooth, sloppy");
                    }
                    yield boxer.settings().withAim(aim);
                }
                case "wtap" -> boxer.settings().withWtap(new BoxerSettings.WTap(
                        parseBoolean("wtap", value),
                        boxer.settings().wtap().delayTicks(),
                        boxer.settings().wtap().releaseTicks()));
                case "preset" -> {
                    BoxerSettings preset = config.snapshot().preset(value);
                    if (preset == null) {
                        throw new IllegalArgumentException("unknown preset '" + value
                                + "'. Known: " + String.join(", ", config.snapshot().presetNames()));
                    }
                    yield preset;
                }
                case "movement" -> boxer.settings().withMovement(new BoxerSettings.Movement(
                        parseStyle(value),
                        boxer.settings().movement().stopDistance(),
                        boxer.settings().movement().sprint()));
                case "invincible" -> boxer.settings().withInvincible(parseBoolean("invincible", value));
                default -> throw new IllegalArgumentException(
                        "keys: " + String.join(", ", SET_KEYS));
            };
            boxer.retune(updated);
            sender.sendMessage("§aSet §f" + key + "§a = §f" + value + "§a on " + boxer.name() + ".");
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage("§c" + invalid.getMessage());
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /boxer info <name>");
            return;
        }
        resolve(sender, args[1]).ifPresent(boxer -> {
            BoxerSettings settings = boxer.settings();
            sender.sendMessage("§6" + boxer.name() + "§7 — "
                    + (boxer.paused() ? "paused" : "active")
                    + ", target " + boxer.target().map(Player::getName).orElse("—"));
            sender.sendMessage("§7 ping §f" + settings.pingMs() + "ms§7, cps §f" + settings.cps()
                    + "§7 (jitter " + settings.clickJitter() + "), reach §f" + settings.reach()
                    + "§7, cone §f" + settings.aimToleranceDegrees() + "°");
            sender.sendMessage("§7 aim §f(k=" + settings.aim().stiffness()
                    + ", d=" + settings.aim().damping()
                    + ", max=" + settings.aim().maxVelocity() + "°/t)");
            sender.sendMessage("§7 w-tap §f" + (settings.wtap().enabled()
                    ? "on (delay " + settings.wtap().delayTicks()
                            + "t, release " + settings.wtap().releaseTicks() + "t)" : "off")
                    + "§7, movement §f"
                    + settings.movement().style().name().toLowerCase(Locale.ROOT)
                    + "§7 (stop " + settings.movement().stopDistance()
                    + ", sprint " + settings.movement().sprint() + ")");
            sender.sendMessage("§7 invincible §f" + settings.invincible()
                    + "§7, fed §f" + settings.feedHunger());
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("simpleboxer.command.reload")) {
            sender.sendMessage("§cYou lack simpleboxer.command.reload.");
            return;
        }
        config.reload();
        sender.sendMessage("§aSimpleBoxer configuration reloaded.");
    }

    private Optional<Boxer> resolve(CommandSender sender, String name) {
        Optional<Boxer> boxer = manager.byName(name);
        if (boxer.isEmpty()) {
            sender.sendMessage("§cNo boxer named '" + name + "'. /boxer list shows them.");
        }
        return boxer;
    }

    /* Plain-prose parsing — never leak a raw JDK exception to the sender. */

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(key + " expects a whole number, not '" + value + "'.");
        }
    }

    private static double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(key + " expects a number, not '" + value + "'.");
        }
    }

    private static boolean parseBoolean(String key, String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> throw new IllegalArgumentException(
                    key + " expects true or false, not '" + value + "'.");
        };
    }

    private static BoxerSettings.Movement.Style parseStyle(String value) {
        try {
            return BoxerSettings.Movement.Style.valueOf(
                    value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "movement styles: rush, strafe-circle, strafe-weave, stand");
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /* ------------------------------------------------------------------ */

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return prefixed(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "remove", "pause", "resume" -> {
                    List<String> names = boxerNames();
                    names.add("all");
                    yield prefixed(names, args[1]);
                }
                case "target", "set", "info" -> prefixed(boxerNames(), args[1]);
                case "spawn" -> List.of();
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "target" -> {
                    List<String> players = new ArrayList<>(
                            Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    players.add("none");
                    yield prefixed(players, args[2]);
                }
                case "set" -> prefixed(SET_KEYS, args[2]);
                case "spawn" -> prefixed(new ArrayList<>(config.snapshot().presetNames()), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("set")) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "aim" -> prefixed(List.of("locked", "sharp", "smooth", "sloppy"), args[3]);
                case "preset" -> prefixed(new ArrayList<>(config.snapshot().presetNames()), args[3]);
                case "movement" -> prefixed(
                        List.of("rush", "strafe-circle", "strafe-weave", "stand"), args[3]);
                case "wtap", "invincible" -> prefixed(List.of("true", "false"), args[3]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> boxerNames() {
        List<String> names = new ArrayList<>();
        for (Boxer boxer : manager.all()) {
            names.add(boxer.name());
        }
        return names;
    }

    private static List<String> prefixed(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matched.add(option);
            }
        }
        return matched;
    }
}

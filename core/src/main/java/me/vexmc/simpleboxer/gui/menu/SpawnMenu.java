package me.vexmc.simpleboxer.gui.menu;

import java.util.Locale;
import me.vexmc.simpleboxer.api.BoxerSpawnRequest;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import me.vexmc.simpleboxer.gui.MenuClick;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Assemble a boxer click-by-click — name, difficulty preset, skin, follow
 * target and starting kit — then drop it at your feet. Replaces the old
 * {@code /boxer spawn <name> [preset] [skin:] [target:] [at ...]} line; every
 * option that command took is a button here, and nothing is mandatory (an
 * unnamed boxer is auto-named on spawn).
 */
final class SpawnMenu extends Menu {

    private final SpawnDraft draft;

    SpawnMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull SpawnDraft draft) {
        super(gui, parent, 5, "§8Spawn a Boxer");
        this.draft = draft;
    }

    @Override
    protected void build() {
        String name = draft.name() != null ? draft.name() : "§8(auto-named)";
        String preset = draft.presetName() != null ? draft.presetName() : "§8defaults";
        String skin = draft.skinOwner() != null ? draft.skinOwner() : "§8default skin";
        String target = draft.targetName() != null ? draft.targetName() : "§8none";

        set(4, Button.display(Icon.of(Material.ARMOR_STAND).glow()
                .name("§6§lNew Boxer")
                .lore("§7Name: §f" + name,
                        "§7Difficulty: §f" + preset,
                        "§7Skin: §f" + skin,
                        "§7Target: §f" + target,
                        "§7Kit: §f" + kitSummary()).build()));

        set(19, Button.of(Icon.of(Material.NAME_TAG)
                        .name("§b§lName")
                        .lore("§7Current: §f" + name,
                                "§8Spawns auto-named if left blank.",
                                "",
                                "§eClick to type a name").build(),
                click -> gui().prompts().prompt(click.player(),
                        "Enter a name for the boxer (1-16 letters, digits or _):",
                        input -> {
                            draft.setName(input);
                            open(click.player());
                        },
                        () -> open(click.player()))));

        set(21, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
                        .name("§b§lDifficulty")
                        .lore("§7Current: §f" + preset,
                                "§8A preset bundles ping, aim, CPS,",
                                "§8reach, w-tap and movement.",
                                "",
                                "§eClick to choose").build(),
                click -> new PresetPickerMenu(gui(), this, "Spawn difficulty", true,
                        chosen -> {
                            draft.setPresetName(chosen);
                            open(click.player());
                        }).open(click.player())));

        set(23, Button.of(Icon.of(Material.PLAYER_HEAD)
                        .name("§b§lSkin")
                        .lore("§7Current: §f" + skin,
                                "§8Wear any account's skin.",
                                "",
                                "§eClick to set §7(self / clear)").build(),
                click -> gui().prompts().prompt(click.player(),
                        "Type an account name for the skin, 'self', or 'clear':",
                        input -> {
                            applySkinInput(input, click.player());
                            open(click.player());
                        },
                        () -> open(click.player()))));

        set(25, Button.of(Icon.of(Material.COMPASS)
                        .name("§b§lTarget")
                        .lore("§7Current: §f" + target,
                                "§8Who the boxer follows and attacks.",
                                "",
                                "§eClick to choose").build(),
                click -> new PlayerPickerMenu(gui(), this, "Spawn target", true,
                        chosen -> {
                            draft.setTargetName(chosen == null ? null : chosen.getName());
                            open(click.player());
                        }).open(click.player())));

        set(29, Button.of(Icon.of(Material.IRON_CHESTPLATE).clean()
                        .name("§b§lStarting Kit")
                        .lore("§7" + kitSummary(),
                                "§8Armor and weapons — custom enchants included.",
                                "",
                                "§eClick to edit the kit").build(),
                click -> LoadoutMenu.forDraft(gui(), this, draft).open(click.player())));

        set(33, Button.of(Icon.of(Material.LIME_CONCRETE).glow()
                        .name("§a§l✔ Spawn")
                        .lore("§7Drop the boxer at your location.",
                                "",
                                "§eClick to spawn").build(),
                this::spawn));

        set(36, Button.of(MenuParts.back(), click -> back(click.player())));
        set(44, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    private @NotNull String kitSummary() {
        int n = draft.loadout().filledSlots();
        return n == 0 ? "empty" : n + " item(s)";
    }

    private void applySkinInput(@NotNull String input, @NotNull Player player) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.equals("clear") || lower.equals("none") || lower.equals("default")) {
            draft.setSkinOwner(null);
        } else if (lower.equals("self") || lower.equals("me")) {
            draft.setSkinOwner(player.getName());
        } else {
            draft.setSkinOwner(input);
        }
    }

    private void spawn(@NotNull MenuClick click) {
        Player player = click.player();
        String name = draft.name() != null ? draft.name() : autoName();
        BoxerSettings settings = resolveSettings();
        Location location = player.getLocation();

        BoxerSpawnRequest request;
        try {
            request = new BoxerSpawnRequest(name, location, settings,
                    draft.skinOwner(), draft.targetName(), draft.loadout());
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("§c" + invalid.getMessage());
            return;
        }

        player.sendMessage("§7Spawning §f" + name + "§7…");
        gui().manager().spawn(request).whenComplete((boxer, failure) ->
                gui().scheduling().runOn(player, () -> {
                    if (failure != null) {
                        player.sendMessage("§cSpawn failed: " + rootMessage(failure));
                        if (player.isOnline()) {
                            open(player);
                        }
                        return;
                    }
                    player.sendMessage("§aSpawned boxer §f" + boxer.name() + "§a.");
                    if (player.isOnline()) {
                        new BoxerMenu(gui(),
                                new BoxerListMenu(gui(), parent()), boxer).open(player);
                    }
                }, () -> {}));
    }

    private @NotNull BoxerSettings resolveSettings() {
        if (draft.presetName() != null) {
            BoxerSettings preset = gui().config().snapshot().preset(draft.presetName());
            if (preset != null) {
                return preset;
            }
        }
        return gui().config().snapshot().defaults();
    }

    private @NotNull String autoName() {
        for (int n = 1; n < 100_000; n++) {
            String candidate = "Boxer" + n;
            if (gui().manager().byName(candidate).isEmpty()
                    && Bukkit.getPlayerExact(candidate) == null) {
                return candidate;
            }
        }
        return "Boxer";
    }

    private static @NotNull String rootMessage(@NotNull Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}

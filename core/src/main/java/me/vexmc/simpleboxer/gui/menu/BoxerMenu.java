package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One boxer's control panel — everything {@code /boxer target|pause|resume|set
 * |remove} did for a single bot, plus teleport and kit editing, all on one
 * screen. Opened from the roster or straight after a spawn.
 */
final class BoxerMenu extends Menu {

    private final Boxer boxer;

    BoxerMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull Boxer boxer) {
        super(gui, parent, 5, "§8Boxer · " + boxer.name());
        this.boxer = boxer;
    }

    @Override
    protected void build() {
        if (boxer.player() == null || gui().manager().byUuid(boxer.uuid()).isEmpty()) {
            // The boxer was removed out from under this screen — bounce back.
            set(22, Button.display(Icon.of(Material.BARRIER)
                    .name("§cThis boxer is gone").build()));
            set(40, Button.of(MenuParts.back(), click -> back(click.player())));
            fillEmpty(MenuParts.BACKGROUND);
            return;
        }

        BoxerSettings settings = boxer.settings();
        String target = boxer.target().map(Player::getName).orElse("§8none");
        boolean down = boxer.state() == Boxer.State.AWAITING_RESPAWN;
        String status = down ? "§c☠ down (awaiting respawn)"
                : (boxer.paused() ? "§e⏸ paused" : "§a▶ active");

        set(4, Button.display(Icon.head(boxer.player())
                .name("§6§l" + boxer.name())
                .lore("§7Status: " + status,
                        "§7Target: §f" + target,
                        "§7Ping §f" + settings.pingMs() + "ms§7, CPS §f"
                                + MenuParts.number(settings.cps()),
                        "§7Reach §f" + MenuParts.number(settings.reach())
                                + "§7, move §f" + MenuParts.prettyStyle(settings.movement().style()),
                        "§7Kit: §f" + boxer.loadout().filledSlots() + " item(s)").build()));

        set(10, Button.of(Icon.of(Material.COMPARATOR)
                        .name("§b§lBehaviour")
                        .lore("§7Ping, aim, CPS, reach, w-tap, movement…",
                                "",
                                "§eClick to tune").build(),
                click -> new SettingsHubMenu(gui(), this, SettingsTarget.forBoxer(boxer))
                        .open(click.player())));

        set(11, Button.of(Icon.of(Material.IRON_CHESTPLATE).clean()
                        .name("§b§lKit")
                        .lore("§7" + boxer.loadout().filledSlots() + " item(s) equipped",
                                "§8Armor and weapons — custom enchants included.",
                                "",
                                "§eClick to edit").build(),
                click -> LoadoutMenu.forBoxer(gui(), this, boxer).open(click.player())));

        set(12, Button.of(Icon.of(Material.COMPASS)
                        .name("§b§lTarget")
                        .lore("§7Current: §f" + target,
                                "",
                                "§eClick to choose").build(),
                click -> new PlayerPickerMenu(gui(), this, "Target for " + boxer.name(), true,
                        chosen -> {
                            boxer.setTarget(chosen);
                            click.player().sendMessage(chosen == null
                                    ? "§aCleared §f" + boxer.name() + "§a's target."
                                    : "§a" + boxer.name() + " now hunts §f" + chosen.getName() + "§a.");
                            open(click.player());
                        }).open(click.player())));

        boolean paused = boxer.paused();
        set(13, Button.of(Icon.of(paused ? Material.LIME_DYE : Material.GRAY_DYE)
                        .name(paused ? "§a§l▶ Resume" : "§e§l⏸ Pause")
                        .lore(paused ? "§7Wake the brain back up." : "§7Freeze the brain.",
                                "§8It still takes knockback while paused.",
                                "",
                                "§eClick to toggle").build(),
                click -> {
                    if (boxer.paused()) {
                        boxer.resume();
                    } else {
                        boxer.pause();
                    }
                    click.refresh();
                }));

        set(14, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
                        .name("§b§lApply Preset")
                        .lore("§7Overwrite this boxer's behaviour with",
                                "§7a whole preset (the kit is untouched).",
                                "",
                                "§eClick to choose").build(),
                click -> new PresetPickerMenu(gui(), this, "Apply to " + boxer.name(), true,
                        chosen -> {
                            BoxerSettings applied = chosen == null
                                    ? gui().config().snapshot().defaults()
                                    : preset(chosen);
                            boxer.retune(applied);
                            click.player().sendMessage("§aApplied §f"
                                    + (chosen == null ? "defaults" : chosen)
                                    + "§a to " + boxer.name() + ".");
                            open(click.player());
                        }).open(click.player())));

        set(15, Button.of(Icon.of(Material.ENDER_PEARL)
                        .name("§b§lTeleport to")
                        .lore("§7Warp yourself to this boxer.",
                                "",
                                "§eClick to teleport").build(),
                click -> {
                    Player operator = click.player();
                    operator.closeInventory();
                    // Snapshot the boxer's position on ITS owning thread (a
                    // cross-region read on Folia is illegal), then teleport the
                    // operator on the operator's own thread.
                    gui().scheduling().runOn(boxer.player(), () -> {
                        Location to = boxer.player().getLocation();
                        gui().scheduling().runOn(operator,
                                () -> teleport(operator, to), () -> {});
                    }, () -> {});
                }));

        set(16, Button.of(Icon.of(Material.LEAD)
                        .name("§b§lBring here")
                        .lore("§7Warp this boxer to you.",
                                "",
                                "§eClick to summon").build(),
                click -> {
                    Location to = click.player().getLocation();
                    gui().scheduling().runOn(boxer.player(),
                            () -> teleport(boxer.player(), to), () -> {});
                    click.player().sendMessage("§aBrought §f" + boxer.name() + "§a to you.");
                    click.refresh();
                }));

        // Only a down boxer (manual-death mode) can be brought back — the button
        // appears exactly while it is useful.
        if (down) {
            set(30, Button.of(Icon.of(Material.RESPAWN_ANCHOR).glow()
                            .name("§a§l✦ Respawn")
                            .lore("§7This boxer died and is waiting.",
                                    "§7Bring it back at its death spot,",
                                    "§7full health, kit re-applied.",
                                    "",
                                    "§eClick to respawn").build(),
                    click -> {
                        boxer.respawn();
                        click.player().sendMessage("§aRespawned §f" + boxer.name() + "§a.");
                        click.refresh();
                    }));
        }

        set(31, Button.of(Icon.of(Material.BARRIER)
                        .name("§c§l☠ Remove")
                        .lore("§7Despawn this boxer for good.",
                                "",
                                "§eClick to remove").build(),
                click -> new ConfirmMenu(gui(), this, "Remove " + boxer.name(), Material.BARRIER,
                        "Remove boxer " + boxer.name() + "?",
                        ConfirmMenu.lines("§7It despawns immediately.", "§7This cannot be undone."),
                        player -> {
                            boxer.remove();
                            player.sendMessage("§aRemoved boxer §f" + boxer.name() + "§a.");
                            Menu list = parent();
                            if (list != null) {
                                list.open(player);
                            } else {
                                gui().openMain(player);
                            }
                        }).open(click.player())));

        set(36, Button.of(MenuParts.back(), click -> back(click.player())));
        set(44, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    private @NotNull BoxerSettings preset(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        return preset != null ? preset : gui().config().snapshot().defaults();
    }

    /** Synchronous teleport, falling back to the async form a Folia region demands. */
    private static void teleport(@NotNull Player player, @NotNull Location to) {
        try {
            player.teleport(to);
        } catch (UnsupportedOperationException foliaRegionThreading) {
            player.teleportAsync(to);
        }
    }
}

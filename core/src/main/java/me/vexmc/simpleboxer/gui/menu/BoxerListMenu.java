package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The live roster — one head per boxer, click to open its control panel. The
 * footer carries the bulk actions the old {@code /boxer pause|resume|remove
 * all} lines did, each one click away.
 */
final class BoxerListMenu extends PaginatedMenu<Boxer> {

    BoxerListMenu(@NotNull Gui gui, @Nullable Menu parent) {
        super(gui, parent, "§8Manage Boxers");
    }

    // Head lore (paused/target/ping) drifts while the roster sits open —
    // re-render once a second rather than only on interaction.
    @Override
    protected int refreshEveryTicks() {
        return 20;
    }

    @Override
    protected @NotNull List<Boxer> items() {
        List<Boxer> all = new ArrayList<>(gui().manager().all());
        all.sort(Comparator.comparing(Boxer::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @Override
    protected @NotNull ItemStack header(int itemCount) {
        return Icon.of(Material.PLAYER_HEAD).name("§6§lManage Boxers")
                .lore("§7" + itemCount + " boxer(s) live",
                        "§8Click a head to control one").build();
    }

    @Override
    protected @NotNull Button render(@NotNull Boxer boxer) {
        String target = boxer.target().map(Player::getName).orElse("§8none");
        org.bukkit.inventory.ItemStack icon = Icon.head(boxer.player())
                .name("§f" + boxer.name() + (boxer.paused() ? " §7[paused]" : ""))
                .lore("§7Target: §f" + target,
                        "§7Ping §f" + boxer.settings().pingMs() + "ms§7, CPS §f"
                                + MenuParts.number(boxer.settings().cps()),
                        "§7Kit: §f" + boxer.loadout().filledSlots() + " item(s)",
                        "",
                        "§eClick to control").build();
        return Button.of(icon, click -> new BoxerMenu(gui(), this, boxer).open(click.player()));
    }

    @Override
    protected void footer() {
        set(46, Button.of(Icon.of(Material.GRAY_DYE).name("§e⏸ Pause all").build(),
                click -> {
                    gui().manager().all().forEach(Boxer::pause);
                    click.refresh();
                }));
        set(47, Button.of(Icon.of(Material.LIME_DYE).name("§e▶ Resume all").build(),
                click -> {
                    gui().manager().all().forEach(Boxer::resume);
                    click.refresh();
                }));

        int live = gui().manager().all().size();
        set(51, Button.of(Icon.of(Material.LAVA_BUCKET).name("§c☠ Remove all").build(),
                click -> new ConfirmMenu(gui(), this, "Remove all boxers", Material.LAVA_BUCKET,
                        "Remove all " + live + " boxer(s)?",
                        ConfirmMenu.lines("§7Every boxer despawns.", "§7This cannot be undone."),
                        player -> {
                            // Remove per boxer: Boxer.remove() schedules the
                            // despawn on each boxer's OWN owning thread, where
                            // manager.removeAll()'s direct loop would mutate
                            // other regions' ServerPlayers off-thread on Folia.
                            for (Boxer each : List.copyOf(gui().manager().all())) {
                                each.remove();
                            }
                            player.sendMessage("§aRemoved every boxer.");
                            open(player);
                        }).open(click.player())));

        set(52, Button.of(Icon.of(Material.ARMOR_STAND).name("§a✚ Spawn a new boxer").build(),
                click -> new SpawnMenu(gui(), this, new SpawnDraft()).open(click.player())));
    }
}

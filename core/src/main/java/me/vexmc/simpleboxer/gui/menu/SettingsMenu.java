package me.vexmc.simpleboxer.gui.menu;

import java.util.List;
import java.util.function.UnaryOperator;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsParser;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsWriter;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import me.vexmc.simpleboxer.gui.MenuClick;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The behaviour editor — every knob the old {@code /boxer set} command (and
 * the config file) exposed, as a grid of click-to-adjust icons. The same
 * screen drives a live boxer (changes retune it instantly) or the persistent
 * {@code defaults}/preset scopes (changes save to {@code config.yml}); the
 * {@link SettingsTarget} hides which.
 */
final class SettingsMenu extends Menu {

    private static final List<String> AIM_ORDER = List.of("locked", "sharp", "smooth", "sloppy");
    private static final List<BoxerSettings.Movement.Style> MOVE_ORDER = List.of(
            BoxerSettings.Movement.Style.RUSH,
            BoxerSettings.Movement.Style.STRAFE_CIRCLE,
            BoxerSettings.Movement.Style.STRAFE_WEAVE,
            BoxerSettings.Movement.Style.STAND);

    private final SettingsTarget target;

    SettingsMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull SettingsTarget target) {
        super(gui, parent, 6, "§8Settings · " + target.label());
        this.target = target;
    }

    @Override
    protected void build() {
        BoxerSettings s = target.settings();

        set(4, Button.display(Icon.of(target.icon()).glow()
                .name("§6§lSettings · " + target.label())
                .lore(target.persistent()
                        ? "§7Changes are saved to §fconfig.yml"
                        : "§7Changes apply to this boxer §finstantly").build()));

        // Row 1 — aim & clicking.
        set(10, numeric(Material.CLOCK, "Ping", s.pingMs() + "ms",
                "§8Simulated round-trip latency (0-2000ms).",
                MenuParts.adjustHint("+10", "-10", "±100"),
                click -> mutate(c -> c.withPingMs(
                        MenuParts.stepInt(c.pingMs(), click.click(), 10, 100, 0, 2000)))));

        set(11, numeric(Material.LEVER, "CPS", MenuParts.number(s.cps()),
                "§8Clicks per second (0-50; 0 never attacks).",
                MenuParts.adjustHint("+0.5", "-0.5", "±1"),
                click -> mutate(c -> c.withCps(
                        MenuParts.step(c.cps(), click.click(), 0.5, 1.0, 0.0, 50.0)))));

        set(12, numeric(Material.SUGAR, "Click jitter", MenuParts.number(s.clickJitter()),
                "§8Per-click interval wobble (0-0.9).",
                MenuParts.adjustHint("+0.05", "-0.05", "±0.1"),
                click -> mutate(c -> c.withClickJitter(
                        MenuParts.step(c.clickJitter(), click.click(), 0.05, 0.1, 0.0, 0.9)))));

        set(13, numeric(Material.FISHING_ROD, "Reach", MenuParts.number(s.reach()),
                "§8Attack range in blocks (0.5-6).",
                MenuParts.adjustHint("+0.1", "-0.1", "±0.5"),
                click -> mutate(c -> c.withReach(
                        MenuParts.step(c.reach(), click.click(), 0.1, 0.5, 0.5, 6.0)))));

        set(14, numeric(Material.SPYGLASS, "Aim cone", MenuParts.number(s.aimToleranceDegrees()) + "°",
                "§8A click only attacks within this cone.",
                MenuParts.adjustHint("+1", "-1", "±10"),
                click -> mutate(c -> c.withAimToleranceDegrees(
                        MenuParts.step(c.aimToleranceDegrees(), click.click(), 1.0, 10.0, 0.0, 180.0)))));

        String aimName = BoxerSettingsWriter.aimPresetName(s.aim());
        set(15, cycler(Material.ENDER_EYE, "Aim spring", aimName == null ? "custom" : aimName,
                "§8How the crosshair chases the target.",
                "§8locked / sharp / smooth / sloppy",
                click -> mutate(c -> c.withAim(cycleAim(c.aim(), click.left())))));

        // Row 2 — w-tap & movement.
        set(19, toggle(Material.FEATHER, "W-tap", s.wtap().enabled(),
                "§8Release+re-press forward after a hit to",
                "§8re-arm sprint knockback.",
                click -> mutate(c -> c.withWtap(new BoxerSettings.WTap(
                        !c.wtap().enabled(), c.wtap().delayTicks(), c.wtap().releaseTicks())))));

        set(20, numeric(Material.REPEATER, "W-tap delay", s.wtap().delayTicks() + "t",
                "§8Ticks after a hit before forward releases (0-20).",
                MenuParts.adjustHint("+1", "-1", "±5"),
                click -> mutate(c -> c.withWtap(new BoxerSettings.WTap(c.wtap().enabled(),
                        MenuParts.stepInt(c.wtap().delayTicks(), click.click(), 1, 5, 0, 20),
                        c.wtap().releaseTicks())))));

        set(21, numeric(Material.REDSTONE, "W-tap release", s.wtap().releaseTicks() + "t",
                "§8Ticks forward stays released (1-20).",
                MenuParts.adjustHint("+1", "-1", "±5"),
                click -> mutate(c -> c.withWtap(new BoxerSettings.WTap(c.wtap().enabled(),
                        c.wtap().delayTicks(),
                        MenuParts.stepInt(c.wtap().releaseTicks(), click.click(), 1, 5, 1, 20))))));

        set(23, cycler(Material.LEATHER_BOOTS, "Movement",
                MenuParts.prettyStyle(s.movement().style()),
                "§8rush / strafe-circle / strafe-weave / stand",
                "§8How the boxer closes distance.",
                click -> mutate(c -> c.withMovement(new BoxerSettings.Movement(
                        cycleStyle(c.movement().style(), click.left()),
                        c.movement().stopDistance(), c.movement().sprint())))));

        set(24, numeric(Material.TARGET, "Stop distance",
                MenuParts.number(s.movement().stopDistance()),
                "§80 = hold W through the target (true rusher).",
                MenuParts.adjustHint("+0.5", "-0.5", "±1"),
                click -> mutate(c -> c.withMovement(new BoxerSettings.Movement(
                        c.movement().style(),
                        MenuParts.step(c.movement().stopDistance(), click.click(), 0.5, 1.0, 0.0, 6.0),
                        c.movement().sprint())))));

        set(25, toggle(Material.RABBIT_FOOT, "Sprint", s.movement().sprint(),
                "§8Hold sprint whenever forward is down.",
                "§8(Speed/Slowness still apply on top.)",
                click -> mutate(c -> c.withMovement(new BoxerSettings.Movement(
                        c.movement().style(), c.movement().stopDistance(),
                        !c.movement().sprint())))));

        // Row 3 — survival policy & whole-preset apply.
        set(29, toggle(Material.TOTEM_OF_UNDYING, "Invincible", s.invincible(),
                "§8Take the full hit (knockback, events),",
                "§8then restore health. Never cancels damage.",
                click -> mutate(c -> c.withInvincible(!c.invincible()))));

        set(30, toggle(Material.COOKED_BEEF, "Feed hunger", s.feedHunger(),
                "§8Pin hunger full so sprint stays legal.",
                "§8",
                click -> mutate(c -> c.withFeedHunger(!c.feedHunger()))));

        set(33, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
                        .name("§b§lApply a preset")
                        .lore("§7Overwrite every setting above with",
                                "§7a whole preset.",
                                "",
                                "§eClick to choose").build(),
                click -> new PresetPickerMenu(gui(), this, "Apply a preset", true,
                        chosen -> {
                            BoxerSettings applied = chosen == null
                                    ? gui().config().snapshot().defaults()
                                    : presetOrDefaults(chosen);
                            target.apply(applied);
                            open(click.player());
                        }).open(click.player())));

        set(45, Button.of(MenuParts.back(), click -> back(click.player())));
        set(53, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    /* ---- icon factories ----------------------------------------------- */

    private @NotNull Button numeric(@NotNull Material material, @NotNull String title,
            @NotNull String value, @NotNull String description, @NotNull String hint,
            @NotNull java.util.function.Consumer<MenuClick> onClick) {
        return Button.of(Icon.of(material).clean()
                .name("§b§l" + title)
                .lore("§7Value: §a" + value, description, "", hint).build(), onClick);
    }

    private @NotNull Button toggle(@NotNull Material material, @NotNull String title,
            boolean value, @NotNull String line1, @NotNull String line2,
            @NotNull java.util.function.Consumer<MenuClick> onClick) {
        return Button.of(Icon.of(material).clean().glow(value)
                .name("§b§l" + title)
                .lore("§7State: " + MenuParts.onOff(value), line1, line2, "",
                        "§8» §7Click to toggle").build(), onClick);
    }

    private @NotNull Button cycler(@NotNull Material material, @NotNull String title,
            @NotNull String value, @NotNull String line1, @NotNull String line2,
            @NotNull java.util.function.Consumer<MenuClick> onClick) {
        return Button.of(Icon.of(material).clean()
                .name("§b§l" + title)
                .lore("§7Value: §a" + value, line1, line2, "",
                        "§8» §7Left: §fnext  §7Right: §fprevious").build(), onClick);
    }

    /* ---- mutation ----------------------------------------------------- */

    private void mutate(@NotNull UnaryOperator<BoxerSettings> change) {
        try {
            target.apply(change.apply(target.settings()));
        } catch (IllegalArgumentException rejected) {
            // A clamp guards every adjuster, so this is defensive only.
        }
        refresh();
    }

    private @NotNull AimParams cycleAim(@NotNull AimParams current, boolean forward) {
        String name = BoxerSettingsWriter.aimPresetName(current);
        int index = name == null ? 1 : AIM_ORDER.indexOf(name);
        if (index < 0) {
            index = 1;
        }
        int next = forward ? (index + 1) % AIM_ORDER.size()
                : (index + AIM_ORDER.size() - 1) % AIM_ORDER.size();
        AimParams resolved = BoxerSettingsParser.aimPresetByName(AIM_ORDER.get(next));
        return resolved != null ? resolved : current;
    }

    private @NotNull BoxerSettings.Movement.Style cycleStyle(
            @NotNull BoxerSettings.Movement.Style current, boolean forward) {
        int index = MOVE_ORDER.indexOf(current);
        if (index < 0) {
            index = 0;
        }
        int next = forward ? (index + 1) % MOVE_ORDER.size()
                : (index + MOVE_ORDER.size() - 1) % MOVE_ORDER.size();
        return MOVE_ORDER.get(next);
    }

    private @NotNull BoxerSettings presetOrDefaults(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        return preset != null ? preset : gui().config().snapshot().defaults();
    }
}

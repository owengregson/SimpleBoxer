package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Items;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The hotbar's role→slot assignment drawn as the hotbar itself — nine
 * buttons, menu slot {@code 9 + n} showing hotbar slot {@code n}. A click
 * cycles what the slot carries (weapon → rod → potion → food → block →
 * empty) with the swap semantics {@link HotbarRoles} enforces, so every role
 * always occupies exactly one slot. This replaces the five near-identical
 * "which slot" integer knobs the Items page used to carry: the assignment is
 * one picture, not five numbers, and two tools can no longer be typed onto
 * the same key.
 *
 * <p>Reached from the Items {@link CategoryMenu} footer. Writes flow through
 * {@link SettingsTarget#apply} like every knob, so the screen serves a live
 * boxer, the defaults and a preset alike.</p>
 */
final class HotbarLayoutMenu extends Menu {

    private final SettingsTarget target;

    HotbarLayoutMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull SettingsTarget target) {
        super(gui, parent, 3, "§8Hotbar layout · " + target.label());
        this.target = target;
    }

    @Override
    protected void build() {
        set(4, Button.display(Icon.of(Material.DIAMOND_SWORD).clean().glow()
                .name("§6§lHotbar layout · " + target.label())
                .lore(target.persistent()
                                ? "§7Changes are saved to §fconfig.yml"
                                : "§7Changes apply to this boxer §finstantly",
                        "",
                        "§7The row below is the boxer's hotbar.",
                        "§7Click a slot to cycle what it carries.").build()));

        Items items = target.settings().items();
        for (int hotbar = 0; hotbar <= 8; hotbar++) {
            set(9 + hotbar, slotButton(items, hotbar));
        }

        set(18, Button.of(MenuParts.back(), click -> back(click.player())));
        set(26, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    private @NotNull Button slotButton(@NotNull Items items, int hotbar) {
        HotbarRoles.Role role = HotbarRoles.roleAt(items, hotbar);
        // Stack size = the keyboard key (1-9) that selects this hotbar slot,
        // so the row reads like the real hotbar at a glance.
        Icon icon = Icon.of(material(role), hotbar + 1).clean()
                .name("§b§lSlot " + (hotbar + 1) + "§7 — " + label(role))
                .lore("§7Carries: " + label(role),
                        "§8Key " + (hotbar + 1) + " · config slot " + hotbar,
                        "",
                        "§8» §7Click to cycle:",
                        "§8weapon → rod → potion → food → block → empty");
        return Button.of(icon.build(), click -> {
            BoxerSettings current = target.settings();
            target.apply(current.withItems(HotbarRoles.cycle(current.items(), hotbar)));
            click.refresh();
        });
    }

    private static @NotNull Material material(@Nullable HotbarRoles.Role role) {
        if (role == null) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
        return switch (role) {
            case WEAPON -> Material.DIAMOND_SWORD;
            case ROD -> Material.FISHING_ROD;
            case POTION -> Material.SPLASH_POTION;
            case FOOD -> Material.COOKED_BEEF;
            case BLOCK -> Material.COBBLESTONE;
        };
    }

    private static @NotNull String label(@Nullable HotbarRoles.Role role) {
        if (role == null) {
            return "§8empty";
        }
        return switch (role) {
            case WEAPON -> "§fWeapon";
            case ROD -> "§fRod";
            case POTION -> "§fPotions";
            case FOOD -> "§fFood";
            case BLOCK -> "§fBlocks";
        };
    }
}

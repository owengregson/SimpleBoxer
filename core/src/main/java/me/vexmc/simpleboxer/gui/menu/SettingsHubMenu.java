package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The behaviour editor's front door: one tile per {@link SettingCategory}, each
 * opening a paginated {@link CategoryMenu} on the same {@link SettingsTarget}.
 * This replaced the old flat 54-slot grid — every knob now lives behind a
 * category, and adding a knob never touches this screen (it belongs to
 * {@link SettingsRegistry}). Drives a live boxer, the {@code defaults} block, or
 * a named preset transparently; the target hides which.
 */
final class SettingsHubMenu extends Menu {

    // Two centred rows of three tiles — enough for the six categories, and room
    // to grow one more row before paging would ever be needed.
    private static final int[] TILE_SLOTS = {20, 22, 24, 29, 31, 33};

    private final SettingsTarget target;

    SettingsHubMenu(@NotNull Gui gui, @Nullable Menu parent, @NotNull SettingsTarget target) {
        super(gui, parent, 6, "§8Settings · " + target.label());
        this.target = target;
    }

    @Override
    protected void build() {
        set(4, Button.display(Icon.of(target.icon()).glow()
                .name("§6§lSettings · " + target.label())
                .lore(target.persistent()
                                ? "§7Changes are saved to §fconfig.yml"
                                : "§7Changes apply to this boxer §finstantly",
                        "",
                        "§7Pick a category below.").build()));

        SettingCategory[] categories = SettingCategory.values();
        for (int i = 0; i < categories.length && i < TILE_SLOTS.length; i++) {
            set(TILE_SLOTS[i], tile(categories[i]));
        }

        // Whole-preset overwrite — the one action that isn't a single knob.
        set(40, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
                        .name("§b§lApply a preset")
                        .lore("§7Overwrite every setting with",
                                "§7a whole preset at once.",
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

    private @NotNull Button tile(@NotNull SettingCategory category) {
        int knobs = SettingsRegistry.byCategory(category).size();
        return Button.of(Icon.of(category.icon()).glow()
                        .name("§b§l" + category.title())
                        .lore(category.summary(),
                                "§8" + knobs + " setting(s)",
                                "",
                                "§eClick to open").build(),
                click -> new CategoryMenu(gui(), this, target, category).open(click.player()));
    }

    private @NotNull BoxerSettings presetOrDefaults(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        return preset != null ? preset : gui().config().snapshot().defaults();
    }
}

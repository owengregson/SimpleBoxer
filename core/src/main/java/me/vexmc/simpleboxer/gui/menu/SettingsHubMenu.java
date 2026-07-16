package me.vexmc.simpleboxer.gui.menu;

import java.util.Locale;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Button;
import me.vexmc.simpleboxer.gui.Gui;
import me.vexmc.simpleboxer.gui.Icon;
import me.vexmc.simpleboxer.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The behaviour editor's front door: one tile per {@link SettingCategory}, each
 * opening a paginated {@link CategoryMenu} on the same {@link SettingsTarget}.
 * This replaced the old flat 54-slot grid — every knob now lives behind a
 * category, and adding a knob never touches this screen (it belongs to
 * {@link SettingsRegistry}); adding a whole category doesn't either, because
 * the tile slots are computed from the enum. Drives a live boxer, the
 * {@code defaults} block, or a named preset transparently; the target hides
 * which. The one scope-aware extra is "Save as preset", which appears only for
 * a live boxer — the reverse of preset-apply, capturing a hand-tuned profile
 * back into {@code config.yml}.
 */
final class SettingsHubMenu extends Menu {

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
        int[] slots = tileSlots(categories.length);
        for (int i = 0; i < categories.length; i++) {
            set(slots[i], tile(categories[i]));
        }

        set(45, Button.of(MenuParts.back(), click -> back(click.player())));

        // Whole-preset overwrite — the one action that isn't a single knob,
        // and the ONLY preset-apply in the GUI (the boxer panel defers here).
        set(48, Button.of(Icon.of(Material.ENCHANTED_BOOK).glow()
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

        // The reverse flow — capture this boxer's hand-tuned profile as a
        // named preset. Defaults/preset scopes already persist every click to
        // config.yml, so the button only appears on a live boxer.
        if (!target.persistent()) {
            set(50, Button.of(Icon.of(Material.WRITABLE_BOOK).glow()
                            .name("§a§lSave as preset")
                            .lore("§7Capture this boxer's current settings",
                                    "§7as a named preset in config.yml.",
                                    "",
                                    "§eClick to name it").build(),
                    click -> gui().prompts().prompt(click.player(),
                            "Type a name for the new preset:",
                            input -> saveAsPreset(input, click.player()),
                            () -> open(click.player()))));
        }

        set(53, Button.of(MenuParts.close(), click -> click.player().closeInventory()));

        fillEmpty(MenuParts.BACKGROUND);
    }

    /**
     * Category tiles in centred rows of three, starting on the second content
     * row — computed from the live category count so a brand-new
     * {@link SettingCategory} constant shows up here without a layout edit.
     * Rows 2-4 hold at most nine tiles; a tenth category fails loudly rather
     * than silently vanishing.
     */
    static int[] tileSlots(int categories) {
        if (categories < 1 || categories > 9) {
            throw new IllegalStateException(
                    "SettingsHubMenu lays out 1-9 category tiles, got " + categories);
        }
        int[] slots = new int[categories];
        int placed = 0;
        for (int row = 0; placed < categories; row++) {
            int base = 18 + row * 9;
            int inRow = Math.min(3, categories - placed);
            // A full row sits at columns 2/4/6; a pair at 3/5; a single at 4.
            int[] columns = inRow == 3 ? new int[] {2, 4, 6}
                    : inRow == 2 ? new int[] {3, 5}
                    : new int[] {4};
            for (int column : columns) {
                slots[placed++] = base + column;
            }
        }
        return slots;
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

    private void saveAsPreset(@NotNull String rawName, @NotNull Player player) {
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            player.sendMessage("§cPreset names are 1-32 chars of letters, digits, - or _.");
            open(player);
            return;
        }
        gui().config().savePreset(name, target.settings());
        player.sendMessage("§aSaved §f" + target.label() + "§a's settings as preset §f"
                + name + "§a.");
        open(player);
    }

    private @NotNull BoxerSettings presetOrDefaults(@NotNull String name) {
        BoxerSettings preset = gui().config().snapshot().preset(name);
        return preset != null ? preset : gui().config().snapshot().defaults();
    }
}

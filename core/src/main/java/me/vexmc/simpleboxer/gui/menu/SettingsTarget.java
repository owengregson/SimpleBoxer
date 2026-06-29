package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.gui.Gui;
import org.jetbrains.annotations.NotNull;

/**
 * What a {@link SettingsMenu} is editing: a live boxer (changes retune it
 * instantly), the {@code defaults} block, or a named preset (changes persist
 * to {@code config.yml}). The menu reads {@link #settings()} to render and
 * calls {@link #apply(BoxerSettings)} on every change — it never needs to know
 * which scope it's pointed at.
 */
interface SettingsTarget {

    @NotNull BoxerSettings settings();

    void apply(@NotNull BoxerSettings settings);

    /** Short scope label for the screen header (e.g. "Rival", "Defaults"). */
    @NotNull String label();

    /** A material that reads the scope at a glance. */
    @NotNull org.bukkit.Material icon();

    /** True when {@link #apply} writes to {@code config.yml} (vs. a live boxer). */
    boolean persistent();

    /* ---- factories ---------------------------------------------------- */

    static @NotNull SettingsTarget forBoxer(@NotNull Boxer boxer) {
        return new SettingsTarget() {
            @Override public @NotNull BoxerSettings settings() {
                return boxer.settings();
            }

            @Override public void apply(@NotNull BoxerSettings settings) {
                boxer.retune(settings);
            }

            @Override public @NotNull String label() {
                return boxer.name();
            }

            @Override public @NotNull org.bukkit.Material icon() {
                return org.bukkit.Material.PLAYER_HEAD;
            }

            @Override public boolean persistent() {
                return false;
            }
        };
    }

    static @NotNull SettingsTarget forDefaults(@NotNull Gui gui) {
        return new SettingsTarget() {
            @Override public @NotNull BoxerSettings settings() {
                return gui.config().snapshot().defaults();
            }

            @Override public void apply(@NotNull BoxerSettings settings) {
                gui.config().saveDefaults(settings);
            }

            @Override public @NotNull String label() {
                return "Defaults";
            }

            @Override public @NotNull org.bukkit.Material icon() {
                return org.bukkit.Material.NETHER_STAR;
            }

            @Override public boolean persistent() {
                return true;
            }
        };
    }

    static @NotNull SettingsTarget forPreset(@NotNull Gui gui, @NotNull String name) {
        return new SettingsTarget() {
            @Override public @NotNull BoxerSettings settings() {
                BoxerSettings preset = gui.config().snapshot().preset(name);
                return preset != null ? preset : gui.config().snapshot().defaults();
            }

            @Override public void apply(@NotNull BoxerSettings settings) {
                gui.config().savePreset(name, settings);
            }

            @Override public @NotNull String label() {
                return "Preset · " + name;
            }

            @Override public @NotNull org.bukkit.Material icon() {
                return org.bukkit.Material.ENCHANTED_BOOK;
            }

            @Override public boolean persistent() {
                return true;
            }
        };
    }
}

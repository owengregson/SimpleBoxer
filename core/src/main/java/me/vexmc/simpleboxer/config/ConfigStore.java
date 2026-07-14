package me.vexmc.simpleboxer.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsParser;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsWriter;
import me.vexmc.simpleboxer.common.settings.DifficultyPresets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable snapshot of the whole configuration, swapped by reference —
 * Mental's atomic-config discipline. A reload parses into a fresh snapshot
 * and publishes it; no code path can read a torn mix.
 */
public final class ConfigStore {

    /** Everything the plugin reads at runtime. */
    public record Snapshot(
            boolean hideFromTab,
            @NotNull BoxerSettings defaults,
            @NotNull Map<String, BoxerSettings> presets) {

        /** Built-ins first, file entries overriding by name. */
        public @Nullable BoxerSettings preset(@NotNull String name) {
            return presets.get(name.toLowerCase(Locale.ROOT));
        }

        public @NotNull Set<String> presetNames() {
            return new TreeSet<>(presets.keySet());
        }
    }

    private final JavaPlugin plugin;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public ConfigStore(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public @NotNull Snapshot snapshot() {
        return current.get();
    }

    /** Re-reads config.yml; warns per malformed key, never fails the swap. */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration file = plugin.getConfig();

        BoxerSettings defaults = BoxerSettingsParser.parse(
                file.getConfigurationSection("defaults"), BoxerSettings.DEFAULTS,
                warning -> plugin.getLogger().warning("config.yml defaults: " + warning));

        Map<String, BoxerSettings> presets = new LinkedHashMap<>();
        DifficultyPresets.all().forEach((name, builtin) ->
                presets.put(name, rebase(builtin, defaults)));
        ConfigurationSection presetSection = file.getConfigurationSection("presets");
        if (presetSection != null) {
            for (String name : presetSection.getKeys(false)) {
                String key = name.toLowerCase(Locale.ROOT);
                presets.put(key, BoxerSettingsParser.parse(
                        presetSection.getConfigurationSection(name), defaults,
                        warning -> plugin.getLogger()
                                .warning("config.yml presets." + name + ": " + warning)));
            }
        }

        current.set(new Snapshot(file.getBoolean("hide-from-tab", true), defaults, presets));
    }

    /* ------------------------------------------------------------------ */
    /*  GUI-driven persistence — write a section, save, republish.         */
    /* ------------------------------------------------------------------ */

    /** Persist the {@code defaults} block and republish the snapshot. */
    public void saveDefaults(@NotNull BoxerSettings settings) {
        FileConfiguration file = plugin.getConfig();
        file.set("defaults", null);
        BoxerSettingsWriter.write(file.createSection("defaults"), settings);
        saveAndReload();
    }

    /**
     * Persist a named preset as a FULL block (not a sparse overlay) so the GUI
     * round-trips its own edits exactly, then republish. The name is normalised
     * to the same lowercase key the parser uses.
     */
    public void savePreset(@NotNull String name, @NotNull BoxerSettings settings) {
        String key = name.toLowerCase(Locale.ROOT);
        FileConfiguration file = plugin.getConfig();
        ConfigurationSection presets = file.getConfigurationSection("presets");
        if (presets == null) {
            presets = file.createSection("presets");
        }
        presets.set(key, null);
        BoxerSettingsWriter.write(presets.createSection(key), settings);
        saveAndReload();
    }

    /**
     * Remove a preset's file entry and republish. A built-in name reverts to
     * its built-in definition (the entry was only ever an override); a custom
     * name disappears entirely.
     */
    public void deletePreset(@NotNull String name) {
        String key = name.toLowerCase(Locale.ROOT);
        FileConfiguration file = plugin.getConfig();
        ConfigurationSection presets = file.getConfigurationSection("presets");
        if (presets != null) {
            presets.set(key, null);
        }
        saveAndReload();
    }

    /** Whether {@code config.yml} carries an explicit entry for this preset. */
    public boolean isFileBacked(@NotNull String name) {
        ConfigurationSection presets =
                plugin.getConfig().getConfigurationSection("presets");
        return presets != null && presets.isConfigurationSection(name.toLowerCase(Locale.ROOT));
    }

    /** Persist the tab-list policy and republish. */
    public void setHideFromTab(boolean hide) {
        plugin.getConfig().set("hide-from-tab", hide);
        saveAndReload();
    }

    private void saveAndReload() {
        plugin.saveConfig();
        reload();
    }

    /**
     * Built-in presets define the LADDER (ping/cps/aim/wtap/movement); the
     * operator's defaults still own the survival policy (invincibility,
     * hunger) unless a preset file entry overrides them explicitly.
     */
    private static BoxerSettings rebase(BoxerSettings builtin, BoxerSettings defaults) {
        // The preset owns how it fights (ladder + techniques + kit behavior); the
        // operator's defaults own the survival policy (does it die, does it starve).
        return new BoxerSettings(builtin.pingMs(), builtin.cps(), builtin.clickJitter(),
                builtin.aim(), builtin.reach(), builtin.aimToleranceDegrees(), builtin.wtap(),
                builtin.movement(), defaults.invincible(), defaults.feedHunger(),
                defaults.invincibleMode(), defaults.death(), builtin.combat(),
                builtin.selfHeal(), builtin.items(), defaults.hunger());
    }
}

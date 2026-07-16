package me.vexmc.simpleboxer.gui.menu;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One tunable knob described as data rather than laid out as a slot. Every
 * descriptor renders through the single generic {@link DescriptorButton}, so
 * exposing a brand-new {@link BoxerSettings} field is a one-line addition to
 * {@link SettingsRegistry} — never another hand-placed icon.
 *
 * <p>A descriptor is one of three {@link Kind kinds}; the kind decides which
 * payload fields carry meaning and how the button reacts to a click, while the
 * shared header fields (id, category, name, icon, help) render the same for all
 * of them. Instances are built through the per-kind static factories so an
 * ill-formed descriptor can't be constructed, and reads/writes always go
 * through {@link BoxerSettings}'s {@code withX(...)} copies — the record stays
 * immutable.</p>
 *
 * <p>A knob may depend on a master toggle ({@link #requires}): the renderer
 * dims it while the master is off, but the click stays live — pre-configuring
 * a dependent value before flipping its master on is deliberate, not an
 * error.</p>
 */
final class SettingDescriptor {

    enum Kind { TOGGLE, NUMERIC, CYCLE }

    /**
     * One choice of a {@link Kind#CYCLE} knob: how it reads, how selecting it
     * rewrites the settings, and how it recognises itself as the current value.
     */
    record CycleOption(@NotNull String label,
            @NotNull UnaryOperator<BoxerSettings> select,
            @NotNull Predicate<BoxerSettings> matches) {}

    /* ---- shared header ------------------------------------------------- */
    // The id is the knob's stable key — the vocabulary config docs and other
    // workstreams' registry edits anchor on. Nothing reads it at runtime, but
    // the strings must survive verbatim in the factory calls.
    private final String id;
    private final SettingCategory category;
    private final Kind kind;
    private final String name;
    private final Material material;
    private final List<String> help;

    /* ---- TOGGLE payload ------------------------------------------------ */
    private final @Nullable Predicate<BoxerSettings> state;
    private final @Nullable UnaryOperator<BoxerSettings> toggle;

    /* ---- NUMERIC payload ----------------------------------------------- */
    private final @Nullable ToDoubleFunction<BoxerSettings> number;
    private final @Nullable BiFunction<BoxerSettings, Double, BoxerSettings> setNumber;
    private final double small;
    private final double big;
    private final double min;
    private final double max;
    private final boolean integer;
    private final String unit;

    /* ---- CYCLE payload ------------------------------------------------- */
    private final @Nullable List<CycleOption> options;
    private final @Nullable String customLabel;

    /* ---- dependency ------------------------------------------------------
       Both null for an independent knob. When set, the renderer dims the tile
       while enabledWhen is false and cites requiresLabel in the lore. */
    private final @Nullable Predicate<BoxerSettings> enabledWhen;
    private final @Nullable String requiresLabel;

    private SettingDescriptor(String id, SettingCategory category, Kind kind, String name,
            Material material, List<String> help,
            @Nullable Predicate<BoxerSettings> state, @Nullable UnaryOperator<BoxerSettings> toggle,
            @Nullable ToDoubleFunction<BoxerSettings> number,
            @Nullable BiFunction<BoxerSettings, Double, BoxerSettings> setNumber,
            double small, double big, double min, double max, boolean integer, String unit,
            @Nullable List<CycleOption> options, @Nullable String customLabel,
            @Nullable Predicate<BoxerSettings> enabledWhen, @Nullable String requiresLabel) {
        this.id = id;
        this.category = category;
        this.kind = kind;
        this.name = name;
        this.material = material;
        this.help = help;
        this.state = state;
        this.toggle = toggle;
        this.number = number;
        this.setNumber = setNumber;
        this.small = small;
        this.big = big;
        this.min = min;
        this.max = max;
        this.integer = integer;
        this.unit = unit;
        this.options = options;
        this.customLabel = customLabel;
        this.enabledWhen = enabledWhen;
        this.requiresLabel = requiresLabel;
    }

    /* ------------------------------------------------------------------ */
    /*  Factories — one per kind                                           */
    /* ------------------------------------------------------------------ */

    /** A boolean knob: click flips it. */
    static @NotNull SettingDescriptor toggle(@NotNull String id, @NotNull SettingCategory category,
            @NotNull String name, @NotNull Material material,
            @NotNull Predicate<BoxerSettings> state, @NotNull UnaryOperator<BoxerSettings> toggle,
            @NotNull String... help) {
        return new SettingDescriptor(id, category, Kind.TOGGLE, name, material, List.of(help),
                state, toggle, null, null, 0, 0, 0, 0, false, "", null, null, null, null);
    }

    /** A real-valued knob stepped by click (or typed via chat). */
    static @NotNull SettingDescriptor number(@NotNull String id, @NotNull SettingCategory category,
            @NotNull String name, @NotNull Material material, @NotNull String unit,
            @NotNull ToDoubleFunction<BoxerSettings> get, double small, double big,
            double min, double max,
            @NotNull BiFunction<BoxerSettings, Double, BoxerSettings> set, @NotNull String... help) {
        return new SettingDescriptor(id, category, Kind.NUMERIC, name, material, List.of(help),
                null, null, get, set, small, big, min, max, false, unit, null, null, null, null);
    }

    /** An integer knob — same as {@link #number} but stepped and displayed whole. */
    static @NotNull SettingDescriptor integer(@NotNull String id, @NotNull SettingCategory category,
            @NotNull String name, @NotNull Material material, @NotNull String unit,
            @NotNull ToDoubleFunction<BoxerSettings> get, int small, int big, int min, int max,
            @NotNull BiFunction<BoxerSettings, Double, BoxerSettings> set, @NotNull String... help) {
        return new SettingDescriptor(id, category, Kind.NUMERIC, name, material, List.of(help),
                null, null, get, set, small, big, min, max, true, unit, null, null, null, null);
    }

    /** A closed set of choices: left cycles forward, right backward. */
    static @NotNull SettingDescriptor cycle(@NotNull String id, @NotNull SettingCategory category,
            @NotNull String name, @NotNull Material material, @NotNull String customLabel,
            @NotNull List<CycleOption> options, @NotNull String... help) {
        return new SettingDescriptor(id, category, Kind.CYCLE, name, material, List.of(help),
                null, null, null, null, 0, 0, 0, 0, false, "", options, customLabel, null, null);
    }

    /**
     * A copy of this descriptor gated on a master toggle. {@code masterName}
     * is the human name the dimmed tile's lore cites ("requires Self-heal");
     * {@code when} reads whether the master is currently on.
     */
    @NotNull SettingDescriptor requires(@NotNull String masterName,
            @NotNull Predicate<BoxerSettings> when) {
        return new SettingDescriptor(id, category, kind, name, material, help,
                state, toggle, number, setNumber, small, big, min, max, integer, unit,
                options, customLabel, when, masterName);
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors (package-private — the renderer's only reader)           */
    /* ------------------------------------------------------------------ */

    @NotNull SettingCategory category() {
        return category;
    }

    @NotNull Kind kind() {
        return kind;
    }

    @NotNull String name() {
        return name;
    }

    @NotNull Material material() {
        return material;
    }

    @NotNull List<String> help() {
        return help;
    }

    /** True while this knob's master toggle (if any) is on. */
    boolean enabledFor(@NotNull BoxerSettings settings) {
        return enabledWhen == null || enabledWhen.test(settings);
    }

    /** The master's human name for the dimmed lore; null for an independent knob. */
    @Nullable String requiresLabel() {
        return requiresLabel;
    }

    @NotNull Predicate<BoxerSettings> state() {
        return java.util.Objects.requireNonNull(state);
    }

    @NotNull UnaryOperator<BoxerSettings> toggle() {
        return java.util.Objects.requireNonNull(toggle);
    }

    @NotNull ToDoubleFunction<BoxerSettings> number() {
        return java.util.Objects.requireNonNull(number);
    }

    @NotNull BiFunction<BoxerSettings, Double, BoxerSettings> setNumber() {
        return java.util.Objects.requireNonNull(setNumber);
    }

    double small() {
        return small;
    }

    double big() {
        return big;
    }

    double min() {
        return min;
    }

    double max() {
        return max;
    }

    boolean integer() {
        return integer;
    }

    @NotNull String unit() {
        return unit;
    }

    @NotNull List<CycleOption> options() {
        return java.util.Objects.requireNonNull(options);
    }

    @NotNull String customLabel() {
        return customLabel == null ? "custom" : customLabel;
    }
}

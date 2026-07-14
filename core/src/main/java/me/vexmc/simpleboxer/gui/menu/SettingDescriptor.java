package me.vexmc.simpleboxer.gui.menu;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
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
 * <p>A descriptor is one of four {@link Kind kinds}; the kind decides which
 * payload fields carry meaning and how the button reacts to a click, while the
 * shared header fields (id, category, name, icon, help) render the same for all
 * of them. Instances are built through the per-kind static factories so an
 * ill-formed descriptor can't be constructed, and reads/writes always go
 * through {@link BoxerSettings}'s {@code withX(...)} copies — the record stays
 * immutable.</p>
 */
final class SettingDescriptor {

    enum Kind { TOGGLE, NUMERIC, CYCLE, TEXT }

    /**
     * One choice of a {@link Kind#CYCLE} knob: how it reads, how selecting it
     * rewrites the settings, and how it recognises itself as the current value.
     */
    record CycleOption(@NotNull String label,
            @NotNull UnaryOperator<BoxerSettings> select,
            @NotNull Predicate<BoxerSettings> matches) {}

    /* ---- shared header ------------------------------------------------- */
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

    /* ---- TEXT payload -------------------------------------------------- */
    private final @Nullable Function<BoxerSettings, String> text;
    private final @Nullable BiFunction<BoxerSettings, String, BoxerSettings> setText;

    private SettingDescriptor(String id, SettingCategory category, Kind kind, String name,
            Material material, List<String> help,
            @Nullable Predicate<BoxerSettings> state, @Nullable UnaryOperator<BoxerSettings> toggle,
            @Nullable ToDoubleFunction<BoxerSettings> number,
            @Nullable BiFunction<BoxerSettings, Double, BoxerSettings> setNumber,
            double small, double big, double min, double max, boolean integer, String unit,
            @Nullable List<CycleOption> options, @Nullable String customLabel,
            @Nullable Function<BoxerSettings, String> text,
            @Nullable BiFunction<BoxerSettings, String, BoxerSettings> setText) {
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
        this.text = text;
        this.setText = setText;
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
     * A free-text knob typed in chat. No {@link BoxerSettings} field needs one
     * today (every knob is a number, a flag or an enum), but the kind exists so
     * a future text field is a single descriptor away — the renderer already
     * handles it.
     */
    static @NotNull SettingDescriptor text(@NotNull String id, @NotNull SettingCategory category,
            @NotNull String name, @NotNull Material material,
            @NotNull Function<BoxerSettings, String> get,
            @NotNull BiFunction<BoxerSettings, String, BoxerSettings> set, @NotNull String... help) {
        return new SettingDescriptor(id, category, Kind.TEXT, name, material, List.of(help),
                null, null, null, null, 0, 0, 0, 0, false, "", null, null, get, set);
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors (package-private — the renderer's only reader)           */
    /* ------------------------------------------------------------------ */

    @NotNull String id() {
        return id;
    }

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

    @NotNull Function<BoxerSettings, String> text() {
        return java.util.Objects.requireNonNull(text);
    }

    @NotNull BiFunction<BoxerSettings, String, BoxerSettings> setText() {
        return java.util.Objects.requireNonNull(setText);
    }
}

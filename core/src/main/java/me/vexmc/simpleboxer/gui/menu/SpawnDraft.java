package me.vexmc.simpleboxer.gui.menu;

import me.vexmc.simpleboxer.api.Loadout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The in-progress configuration of a boxer being assembled in the spawn menu,
 * before it becomes a {@code BoxerSpawnRequest}. Everything optional defaults
 * to a sensible blank: no name (one is generated at spawn), the {@code
 * defaults} behaviour preset, the parity skin, no follow target, an empty kit.
 */
final class SpawnDraft {

    private @Nullable String name;
    private @Nullable String presetName;
    private @Nullable String skinOwner;
    private @Nullable String targetName;
    private @NotNull Loadout loadout = Loadout.EMPTY;

    @Nullable String name() {
        return name;
    }

    void setName(@Nullable String name) {
        this.name = name == null || name.isBlank() ? null : name.trim();
    }

    /** The chosen behaviour preset key, or {@code null} for {@code defaults}. */
    @Nullable String presetName() {
        return presetName;
    }

    void setPresetName(@Nullable String presetName) {
        this.presetName = presetName;
    }

    @Nullable String skinOwner() {
        return skinOwner;
    }

    void setSkinOwner(@Nullable String skinOwner) {
        this.skinOwner = skinOwner == null || skinOwner.isBlank() ? null : skinOwner.trim();
    }

    @Nullable String targetName() {
        return targetName;
    }

    void setTargetName(@Nullable String targetName) {
        this.targetName = targetName == null || targetName.isBlank() ? null : targetName.trim();
    }

    @NotNull Loadout loadout() {
        return loadout;
    }

    void setLoadout(@NotNull Loadout loadout) {
        this.loadout = loadout;
    }
}

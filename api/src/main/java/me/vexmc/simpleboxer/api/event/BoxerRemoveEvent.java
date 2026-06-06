package me.vexmc.simpleboxer.api.event;

import me.vexmc.simpleboxer.api.Boxer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the main thread immediately before a boxer despawns. */
public final class BoxerRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Boxer boxer;

    public BoxerRemoveEvent(@NotNull Boxer boxer) {
        this.boxer = boxer;
    }

    public @NotNull Boxer boxer() {
        return boxer;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}

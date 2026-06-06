package me.vexmc.simpleboxer.api.event;

import me.vexmc.simpleboxer.api.Boxer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the main thread after a boxer's player entity is live. */
public final class BoxerSpawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Boxer boxer;

    public BoxerSpawnEvent(@NotNull Boxer boxer) {
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

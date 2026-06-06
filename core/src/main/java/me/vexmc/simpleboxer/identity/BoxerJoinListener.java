package me.vexmc.simpleboxer.identity;

import me.vexmc.simpleboxer.api.Boxer;
import me.vexmc.simpleboxer.api.BoxerService;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps boxers off late joiners' tab lists on the legacy (≤1.19.2) path —
 * the info-remove is per-viewer there, so each new viewer needs their own,
 * delayed long enough for the boxer's skin to have loaded on their client.
 * Modern servers replicate LISTED=false automatically and skip all of this.
 */
public final class BoxerJoinListener implements Listener {

    private static final long SKIN_LOAD_DELAY_TICKS = 40L;

    private final BoxerService boxers;
    private final TabConcealer tabConcealer;
    private final Scheduling scheduling;

    public BoxerJoinListener(@NotNull BoxerService boxers, @NotNull TabConcealer tabConcealer,
            @NotNull Scheduling scheduling) {
        this.boxers = boxers;
        this.tabConcealer = tabConcealer;
        this.scheduling = scheduling;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (boxers.isBoxer(event.getPlayer().getUniqueId())) {
            return;
        }
        // Listing is per-viewer on EVERY version — each joiner needs their
        // own hide. Modern (unlistPlayer) flips immediately; the legacy
        // info-remove waits out the viewer's skin load first.
        Player viewer = event.getPlayer();
        long delay = tabConcealer.usesLegacyPath() ? SKIN_LOAD_DELAY_TICKS : 1L;
        scheduling.runLaterOn(viewer, delay, () -> {
            for (Boxer boxer : boxers.all()) {
                tabConcealer.hideFrom(boxer.player(), viewer);
            }
        }, () -> {});
    }
}

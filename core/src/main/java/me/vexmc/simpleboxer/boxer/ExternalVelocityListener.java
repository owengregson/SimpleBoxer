package me.vexmc.simpleboxer.boxer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The unified capture for any server-side velocity applied to a boxer through
 * {@code PlayerVelocityEvent} — a combat plugin's authoritative knockback
 * (Mental's delivery desk is the sole writer of this event), StarEnchants'
 * additive {@code setVelocity}, or vanilla's own player knockback — read at
 * MONITOR as the FINAL absolute value Bukkit will apply.
 *
 * <p>This is the signal a viewerless boxer previously lost on modern Paper (where
 * the {@code hurtMarked} poll is disabled): a plugin's {@code setVelocity} never
 * reached the client physics. It is fed to the boxer's {@code KnockbackResolver}
 * on the highest-authority channel, which deduplicates it against the melee /
 * motion-echo channels so a single knock is never applied twice.</p>
 */
public final class ExternalVelocityListener implements Listener {

    private final BoxerManager manager;

    public ExternalVelocityListener(@NotNull BoxerManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        // A cancelled velocity is not applied to the real player (an anti-knockback
        // or region plugin vetoed it) — so the boxer must not fly off either.
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
            Vector velocity = event.getVelocity();
            boxer.onExternalVelocity(velocity.getX(), velocity.getY(), velocity.getZ());
        });
    }
}

package me.vexmc.simpleboxer.boxer;

import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Proper invincibility that no burst can defeat. The historical bug was that
 * health was topped up only on the NEXT tick, so a hit larger than current health
 * killed the boxer first. The fix is a same-tick lethal-overflow CAP: when a hit
 * would drop the boxer at or below a small floor, the damage is trimmed so the
 * boxer survives at ~1 HP — crucially still &gt; 0, so the hit registers and its
 * knockback still flows (unlike a full cancel/zero, which vanilla may treat as
 * "not hurt" and skip the knockback for). The next-tick top-up then restores the
 * boxer to full, so the net effect is "takes no lasting damage but still takes
 * knockback". {@code LEGACY_RESTORE} keeps the old restore-only behaviour for
 * anyone deliberately testing exact damage numbers; both are only active when the
 * boxer is {@link BoxerSettings#invincible()}.
 */
public final class InvincibilityGuard implements Listener {

    private final BoxerManager manager;
    private final Scheduling scheduling;

    public InvincibilityGuard(@NotNull BoxerManager manager, @NotNull Scheduling scheduling) {
        this.manager = manager;
        this.scheduling = scheduling;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        manager.byUuidInternal(player.getUniqueId()).ifPresent(boxer -> {
            BoxerSettings settings = boxer.settings();
            if (!settings.invincible()) {
                return;
            }
            if (settings.invincibleMode() == BoxerSettings.InvincibleMode.ZERO_DAMAGE) {
                double health = player.getHealth();
                // Cap an otherwise-lethal hit so the boxer survives — but leave it
                // > 0 so knockback/i-frames still register (a full zero can make
                // vanilla skip the melee knockback entirely).
                if (event.getFinalDamage() >= health) {
                    event.setDamage(Math.max(0.0, health - 1.0));
                }
            }
            // Either mode restores to full afterwards; the cap above is what makes
            // ZERO_DAMAGE burst-proof (LEGACY_RESTORE relies on the restore alone).
            scheduling.runOn(player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    player.setHealth(maxHealth(player));
                }
            }, () -> {});
        });
    }

    @SuppressWarnings("deprecation") // GENERIC_MAX_HEALTH rename across the range
    private static double maxHealth(Player player) {
        for (Attribute attribute : Attribute.values()) {
            if (attribute.name().contains("MAX_HEALTH")) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    return instance.getValue();
                }
            }
        }
        return 20.0;
    }
}

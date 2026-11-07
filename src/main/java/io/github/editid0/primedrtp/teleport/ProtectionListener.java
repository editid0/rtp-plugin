package io.github.editid0.primedrtp.teleport;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-arrival protection:
 * <ul>
 *   <li>invulnerability — every damage event is cancelled until the window expires;</li>
 *   <li>fall-damage protection — the FIRST fall damage within the window is cancelled, then it disarms.</li>
 * </ul>
 * Windows are stored as expiry epoch millis per UUID (concurrent maps), removed on expiry/use/quit.
 */
final class ProtectionListener implements Listener {

    private final Map<UUID, Long> invulnerableUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallProtectedUntil = new ConcurrentHashMap<>();

    /** Arms both windows (a ZERO/negative duration disables that protection). */
    void arm(UUID player, Duration invulnerability, Duration fallProtection) {
        long now = System.currentTimeMillis();
        if (invulnerability.isPositive()) {
            invulnerableUntil.put(player, now + invulnerability.toMillis());
        } else {
            invulnerableUntil.remove(player);
        }
        if (fallProtection.isPositive()) {
            fallProtectedUntil.put(player, now + fallProtection.toMillis());
        } else {
            fallProtectedUntil.remove(player);
        }
    }

    void clear() {
        invulnerableUntil.clear();
        fallProtectedUntil.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (invulnerableUntil.isEmpty() && fallProtectedUntil.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (active(invulnerableUntil, id, now)) {
            event.setCancelled(true);
            // A protected fall during invulnerability still consumes the fall protection.
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                fallProtectedUntil.remove(id);
            }
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && active(fallProtectedUntil, id, now)) {
            fallProtectedUntil.remove(id);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        invulnerableUntil.remove(id);
        fallProtectedUntil.remove(id);
    }

    private static boolean active(Map<UUID, Long> map, UUID id, long now) {
        Long until = map.get(id);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            map.remove(id, until);
            return false;
        }
        return true;
    }
}

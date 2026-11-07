package io.github.editid0.primedrtp.listener;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.api.RtpCause;
import io.github.editid0.primedrtp.config.PluginSettings;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.github.editid0.primedrtp.pool.PooledLocation;
import io.github.editid0.primedrtp.teleport.TeleportRequest;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Automatic random teleports ({@code triggers.first-join} / {@code triggers.respawn} in config.yml).
 * <ul>
 *   <li><b>First join:</b> players who never played before are sent (forced: no cooldown/cost/warmup) after the
 *       configured delay, to the configured world or the normally resolved one.</li>
 *   <li><b>Respawn:</b> after death (not when leaving the End), optionally only without a bed/anchor. If the target
 *       world's pool has a ready location, it becomes the respawn location directly, so the player spawns there
 *       with zero extra loading; otherwise a forced RTP runs one tick after respawning.</li>
 * </ul>
 */
public final class TriggerListener implements Listener {

    /** How long a pooled location used as respawn point keeps its chunk tickets (player's own tickets take over). */
    private static final Duration RESPAWN_RELEASE_DELAY = Duration.ofSeconds(5);

    private final PrimedRTP plugin;

    public TriggerListener(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    // ---- first join ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginSettings settings = plugin.settings();
        if (settings == null) {
            return;
        }
        PluginSettings.FirstJoinTrigger cfg = settings.triggers().firstJoin();
        Player player = event.getPlayer();
        if (!cfg.enabled() || player.hasPlayedBefore()) {
            return;
        }
        long ticks = Math.max(1L, cfg.delay().toMillis() / 50L);
        plugin.scheduling().entityLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            World world = configuredWorld(cfg.world(), "first-join");
            plugin.messages().send(player, "trigger.first-join",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("world", world != null ? world.getName() : player.getWorld().getName()));
            plugin.teleports().teleport(TeleportRequest.forced(player, world, null, RtpCause.FIRST_JOIN))
                    .whenComplete((outcome, error) -> logFailure(error, "first-join", player));
        }, null, ticks);
    }

    // ---- respawn ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        PluginSettings settings = plugin.settings();
        if (settings == null) {
            return;
        }
        PluginSettings.RespawnTrigger cfg = settings.triggers().respawn();
        if (!cfg.enabled() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        if (cfg.onlyWithoutBed() && (event.isBedSpawn() || event.isAnchorSpawn())) {
            return;
        }
        World world = respawnTarget(settings, cfg, event.getRespawnLocation());
        if (world == null) {
            return;
        }
        Player player = event.getPlayer();

        // Instant path: the pooled spot's chunks are generated and ticket-loaded, so respawning there costs nothing.
        Optional<PooledLocation> pooled = plugin.pools().take(world);
        if (pooled.isPresent() && pooled.get().worldId().equals(world.getUID()) && stillSafe(pooled.get(), world)) {
            PooledLocation entry = pooled.get();
            event.setRespawnLocation(entry.toLocation(world));
            plugin.scheduling().asyncLater(() -> plugin.pools().release(entry), RESPAWN_RELEASE_DELAY);
            plugin.scheduling().entityLater(player, () -> sendRespawnMessage(player, world), null, 1L);
            plugin.debug("Respawned " + player.getName() + " at pooled location #" + entry.id() + " in " + world.getName());
            return;
        }
        pooled.ifPresent(plugin.pools()::release); // world mismatch or no longer safe: free it

        // Fallback: normal forced RTP right after the respawn completed.
        plugin.scheduling().entityLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            sendRespawnMessage(player, world);
            plugin.teleports().teleport(TeleportRequest.forced(player, world, null, RtpCause.RESPAWN))
                    .whenComplete((outcome, error) -> logFailure(error, "respawn", player));
        }, null, 1L);
    }

    /**
     * World to respawn-RTP into: the configured world, else the world the player would respawn in (after
     * world-redirects). Null if that world is not loaded or not RTP-enabled.
     */
    private @Nullable World respawnTarget(PluginSettings settings, PluginSettings.RespawnTrigger cfg, Location respawn) {
        World world = configuredWorld(cfg.world(), "respawn");
        if (world == null) {
            world = respawn.getWorld();
            if (world != null) {
                String redirect = settings.worldRedirects().get(world.getName());
                World redirected = redirect == null ? null : Bukkit.getWorld(redirect);
                if (redirected != null) {
                    world = redirected;
                }
            }
        }
        if (world == null) {
            return null;
        }
        Optional<WorldSettings> ws = settings.world(world.getName());
        if (ws.isEmpty() || !ws.get().enabled()) {
            plugin.debug("Respawn RTP skipped: " + world.getName() + " is not RTP-enabled");
            return null;
        }
        return world;
    }

    private void sendRespawnMessage(Player player, World world) {
        plugin.messages().send(player, "trigger.respawn",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("world", world.getName()));
    }

    /** Loaded world for a configured name, or null (blank name, or not loaded — logged). */
    private @Nullable World configuredWorld(@Nullable String name, String trigger) {
        if (name == null || name.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(name);
        if (world == null) {
            plugin.getLogger().warning("triggers." + trigger + ".world '" + name + "' is not loaded; using the default target.");
        }
        return world;
    }

    private void logFailure(@Nullable Throwable error, String trigger, Player player) {
        if (error != null) {
            plugin.getLogger().log(Level.WARNING, trigger + " RTP of " + player.getName() + " failed", error);
        }
    }

    /**
     * Last-moment safety re-check of a pooled respawn spot. Possible only when this thread owns the spot's chunk
     * (always on Paper; on Folia only if it's in the same region) — otherwise the pool's own validation is trusted.
     */
    private boolean stillSafe(PooledLocation entry, World world) {
        if (!plugin.scheduling().owns(world, entry.chunkX(), entry.chunkZ())
                || !world.isChunkLoaded(entry.chunkX(), entry.chunkZ())) {
            return true;
        }
        return plugin.settings().world(world.getName())
                .map(ws -> plugin.searcher().isStillSafe(entry.toLocation(world), ws))
                .orElse(false);
    }
}

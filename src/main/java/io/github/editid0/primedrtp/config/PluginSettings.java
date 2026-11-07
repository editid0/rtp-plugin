package io.github.editid0.primedrtp.config;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Global, immutable plugin settings parsed from config.yml by {@link ConfigLoader}. A new instance is produced on
 * every reload; services must re-read {@code plugin.settings()} rather than caching it across reloads.
 */
public record PluginSettings(
        int configVersion,
        boolean debug,
        /* World used by /rtp when the sender's current world is not RTP-enabled; null = sender's world only. */
        @Nullable String defaultWorld,
        /* "from world" -> "target world"; e.g. world_nether -> world makes /rtp from the nether go to overworld. */
        Map<String, String> worldRedirects,
        /* Extra aliases for /rtp (requires restart). */
        List<String> commandAliases,
        PerformanceSettings performance,
        StorageSettings storage,
        TriggerSettings triggers,
        /* Template applied to worlds not listed under "worlds" when enableUnlistedWorlds is true. */
        WorldSettings defaults,
        boolean enableUnlistedWorlds,
        /* Explicitly configured worlds, keyed by exact world name. */
        Map<String, WorldSettings> worlds
) {

    /** Settings for a world, falling back to {@link #defaults()} if unlisted worlds are enabled. */
    public Optional<WorldSettings> world(String worldName) {
        WorldSettings ws = worlds.get(worldName);
        if (ws != null) {
            return Optional.of(ws);
        }
        return enableUnlistedWorlds ? Optional.of(defaults.withWorldName(worldName)) : Optional.empty();
    }

    public record PerformanceSettings(
            /* Max chunk loads/generations in flight at once for BACKGROUND work (pool filling, pregen). */
            int maxConcurrentChunkLoads,
            /* Token-bucket cap on BACKGROUND chunk loads started per second. */
            int maxChunkLoadsPerSecond,
            /* Pause BACKGROUND work while average MSPT exceeds this (0 = never pause). */
            double pauseAboveMspt,
            /* Pause BACKGROUND work while no players are online. */
            boolean pauseWhenEmpty,
            /* Delay after server start before pool filling begins. */
            Duration startupDelay,
            /* How often the pool manager checks for pools needing refill. */
            Duration refillCheckInterval,
            /* Max concurrent background searches per world. */
            int maxSearchesPerWorld
    ) {}

    public record StorageSettings(
            /* Save pooled locations on shutdown and reload them (re-validated) on startup. */
            boolean persistPools,
            /* Save cooldowns across restarts. */
            boolean persistCooldowns,
            Duration autosaveInterval
    ) {}

    public record FirstJoinTrigger(boolean enabled, @Nullable String world, Duration delay) {}

    public record RespawnTrigger(
            boolean enabled,
            @Nullable String world,
            /* Only when the player has no bed/respawn anchor. */
            boolean onlyWithoutBed
    ) {}

    public record SignTrigger(
            boolean enabled,
            /* First line players write to create a sign, case-insensitive, e.g. "[RTP]". */
            String header,
            /* Permission to create RTP signs. */
            String createPermission
    ) {}

    public record TriggerSettings(FirstJoinTrigger firstJoin, RespawnTrigger respawn, SignTrigger signs) {}
}

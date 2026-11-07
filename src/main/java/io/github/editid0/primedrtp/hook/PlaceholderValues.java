package io.github.editid0.primedrtp.hook;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.github.editid0.primedrtp.pool.PoolStats;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;

/**
 * Computes placeholder values from plugin state. Independent of PlaceholderAPI so other integrations can reuse it.
 * Only uses thread-safe services (cooldowns, pools, teleports) and read-only player state, because placeholder
 * plugins may request values from async threads (scoreboards, tab lists).
 */
public final class PlaceholderValues {

    private final PrimedRTP plugin;

    public PlaceholderValues(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    /**
     * @param player online player, or null (offline / no player context)
     * @return the value, or null if the placeholder is unknown or needs a player that isn't available
     */
    public @Nullable String resolve(@Nullable Player player, PlaceholderRequest request) {
        if (request.kind() == PlaceholderRequest.Kind.PENDING) {
            return player == null ? null : Boolean.toString(plugin.teleports().hasPending(player));
        }
        World world = world(player, request.world());
        if (world == null) {
            return "";
        }
        return switch (request.kind()) {
            case POOL_SIZE -> Integer.toString(plugin.pools().size(world));
            case POOL_TARGET -> Integer.toString(stats(world).target());
            case POOL_PERCENT -> Integer.toString((int) Math.round(stats(world).fillRatio() * 100));
            case COOLDOWN, COOLDOWN_SECONDS, READY -> cooldown(player, world, request.kind());
            case PENDING -> throw new AssertionError("handled above");
        };
    }

    private @Nullable String cooldown(@Nullable Player player, World world, PlaceholderRequest.Kind kind) {
        if (player == null) {
            return null;
        }
        Optional<WorldSettings> ws = plugin.settings().world(world.getName());
        Duration remaining = ws.map(s -> plugin.cooldowns().remaining(player, s)).orElse(Duration.ZERO);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return switch (kind) {
            case COOLDOWN -> plugin.messages().formatDuration(remaining);
            case COOLDOWN_SECONDS -> Long.toString(ceilSeconds(remaining));
            case READY -> Boolean.toString(remaining.isZero());
            default -> null;
        };
    }

    private PoolStats stats(World world) {
        return plugin.pools().stats(world);
    }

    private @Nullable World world(@Nullable Player player, @Nullable String name) {
        if (name != null) {
            return Bukkit.getWorld(name);
        }
        if (player == null) {
            return null;
        }
        return plugin.teleports().resolveTargetWorld(player, null).orElse(null);
    }

    static long ceilSeconds(Duration d) {
        long s = d.getSeconds();
        return d.getNano() > 0 ? s + 1 : s;
    }
}

package io.github.editid0.primedrtp.api;

import io.github.editid0.primedrtp.pool.PoolStats;
import io.github.editid0.primedrtp.teleport.TeleportOutcome;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API. Obtain with {@code Bukkit.getServicesManager().load(PrimedRTPApi.class)}.
 * All methods are thread-safe.
 */
public interface PrimedRTPApi {

    /**
     * Random-teleports a player. When {@code force} is true, permission, cooldown, cost and warmup are skipped.
     * {@code world} null = resolve like /rtp.
     */
    CompletableFuture<TeleportOutcome> teleport(Player player, @Nullable World world, boolean force);

    /** Finds a safe random location without teleporting anyone (empty on failure). */
    CompletableFuture<Optional<Location>> findLocation(World world);

    /** Pool statistics for a world. */
    PoolStats poolStats(World world);

    void registerFilter(LocationFilter filter);

    void unregisterFilter(LocationFilter filter);
}

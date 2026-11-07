package io.github.editid0.primedrtp.pool;

import io.github.editid0.primedrtp.Service;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;

/**
 * Keeps, per RTP-enabled world, a pool of ready-to-use safe locations whose chunks are already generated and held
 * loaded, so a teleport is instant. Refills in the background through {@link io.github.editid0.primedrtp.search.ChunkLoader}
 * (throttled). All methods are thread-safe.
 */
public interface PoolManager extends Service {

    /**
     * Removes and returns a ready location, or empty if the pool is empty/disabled. Ownership passes to the caller,
     * who MUST call {@link #release(PooledLocation)} once done (after the teleport completed or failed) so the
     * chunk tickets are freed. Taking triggers a refill.
     */
    Optional<PooledLocation> take(World world);

    /** Frees chunk tickets held for a taken location (delayed slightly so the arriving player's own tickets apply). */
    void release(PooledLocation location);

    /**
     * Returns an unused taken location (e.g. warmup cancelled) to the front of its pool, keeping its tickets.
     * If the pool is full, disabled or the entry expired, it is released instead.
     */
    void restore(PooledLocation location);

    int size(World world);

    PoolStats stats(World world);

    List<PoolStats> allStats();

    /** Drops every pooled location for the world (releasing tickets); refilling restarts. */
    void clear(World world);

    /** Starts refilling immediately, ignoring the refill-check interval (still throttled). */
    void refillNow(World world);
}

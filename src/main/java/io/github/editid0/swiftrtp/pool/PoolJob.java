package io.github.editid0.swiftrtp.pool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One in-progress refill pipeline (search &rarr; pregenerate &rarr; ticket &rarr; re-validate &rarr; commit) or
 * restore of a persisted location. Owns the ticket references acquired so far until they are transferred to a
 * {@link PoolEntry} or released on cancellation. Thread-safe; lock order is pool &rarr; job &rarr; ticket refs.
 */
final class PoolJob {

    private final UUID worldId;
    private final int generation;
    private final boolean restore;
    private final long startedAt;
    private final TicketRefs refs;

    private long[] acquired = new long[8];
    private int acquiredCount;
    private boolean cancelled;
    private boolean transferred;
    /** Chunk loads this job is waiting for; cancelled with the job so queued ones never spend a load token. */
    private List<CompletableFuture<?>> loads = new ArrayList<>();

    /** Block position reserved for min-spacing once known (search done / restore). */
    private volatile boolean reserved;
    private volatile int reservedX;
    private volatile int reservedZ;

    PoolJob(UUID worldId, int generation, boolean restore, long startedAt, TicketRefs refs) {
        this.worldId = worldId;
        this.generation = generation;
        this.restore = restore;
        this.startedAt = startedAt;
        this.refs = refs;
    }

    int generation() {
        return generation;
    }

    boolean isRestore() {
        return restore;
    }

    long startedAt() {
        return startedAt;
    }

    /** Takes one ticket reference for this job unless it was cancelled/transferred. */
    synchronized boolean acquire(int chunkX, int chunkZ) {
        if (cancelled || transferred) {
            return false;
        }
        refs.acquire(worldId, chunkX, chunkZ);
        if (acquiredCount == acquired.length) {
            acquired = Arrays.copyOf(acquired, acquired.length * 2);
        }
        acquired[acquiredCount++] = ChunkArea.key(chunkX, chunkZ);
        return true;
    }

    /**
     * Registers a chunk load this job waits for, so {@link #cancel()} can abandon it (the loader drops cancelled
     * requests that are still queued). If the job is already cancelled the load is cancelled immediately.
     */
    void trackLoad(CompletableFuture<?> load) {
        boolean cancelNow;
        synchronized (this) {
            cancelNow = cancelled;
            if (!cancelNow) {
                loads.removeIf(CompletableFuture::isDone);
                loads.add(load);
            }
        }
        if (cancelNow) {
            load.cancel(false);
        }
    }

    /** Releases everything acquired and abandons pending loads; later acquires become no-ops. Idempotent. */
    void cancel() {
        List<CompletableFuture<?>> pending;
        synchronized (this) {
            pending = loads;
            loads = new ArrayList<>(0);
            boolean owned = !cancelled && !transferred;
            cancelled = true;
            if (owned) {
                refs.releaseAll(worldId, Arrays.copyOf(acquired, acquiredCount));
                acquiredCount = 0;
            }
        }
        // Outside the lock: cancelling runs dependents synchronously (they may take the pool lock; order is
        // pool -> job, never job -> pool).
        for (CompletableFuture<?> f : pending) {
            f.cancel(false);
        }
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Moves ownership of the acquired references to the caller, or returns null if cancelled. */
    synchronized long[] transfer() {
        if (cancelled || transferred) {
            return null;
        }
        transferred = true;
        long[] out = Arrays.copyOf(acquired, acquiredCount);
        acquiredCount = 0;
        return out;
    }

    void reserve(int blockX, int blockZ) {
        reservedX = blockX;
        reservedZ = blockZ;
        reserved = true;
    }

    boolean isReserved() {
        return reserved;
    }

    int reservedX() {
        return reservedX;
    }

    int reservedZ() {
        return reservedZ;
    }
}

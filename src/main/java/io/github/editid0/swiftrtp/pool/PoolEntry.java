package io.github.editid0.swiftrtp.pool;

/**
 * A ready pooled location plus the bookkeeping needed to free it.
 *
 * @param location     the public value handed to consumers
 * @param ticketChunks chunk keys ({@link ChunkArea#key}) this entry holds one ticket reference on; owned by the entry
 * @param generation   {@link WorldPool} generation it was created in (a clear/settings change bumps it)
 */
record PoolEntry(PooledLocation location, long[] ticketChunks, int generation) {

    boolean isExpired(long now, long expireAfterMillis) {
        return expireAfterMillis > 0 && now - location.createdAt() >= expireAfterMillis;
    }
}

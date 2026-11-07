package io.github.editid0.swiftrtp.pool;

import java.util.UUID;

/** Reference-counted chunk ticket holder; implemented by {@link ChunkTicketTracker}, faked in tests. */
interface TicketRefs {

    /** Adds one reference; callable from any thread. */
    void acquire(UUID world, int chunkX, int chunkZ);

    /** Drops one reference; callable from any thread. Unknown references are ignored. */
    void release(UUID world, int chunkX, int chunkZ);

    default void releaseAll(UUID world, long[] chunkKeys) {
        for (long key : chunkKeys) {
            release(world, ChunkArea.keyX(key), ChunkArea.keyZ(key));
        }
    }
}

package io.github.editid0.primedrtp.search;

import org.jspecify.annotations.Nullable;

/**
 * @param priority     URGENT when a player is waiting on this search, BACKGROUND for pool filling
 * @param maxAttempts  overrides {@code search.max-attempts} when &gt; 0
 * @param avoidNear    extra positions (block X,Z pairs packed via {@link #pack}) to stay {@code avoidRadius} blocks
 *                     away from — used by the pool to enforce {@code pool.min-spacing}; may be null
 * @param avoidRadius  blocks; ignored when avoidNear is null
 */
public record SearchOptions(ChunkLoader.Priority priority, int maxAttempts, long @Nullable [] avoidNear, int avoidRadius) {

    public static SearchOptions urgent() {
        return new SearchOptions(ChunkLoader.Priority.URGENT, 0, null, 0);
    }

    public static SearchOptions background() {
        return new SearchOptions(ChunkLoader.Priority.BACKGROUND, 0, null, 0);
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }
}

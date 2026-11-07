package io.github.editid0.primedrtp.pool;

/**
 * Snapshot of one world's pool, for /rtp admin pool, the GUI and placeholders.
 */
public record PoolStats(
        String worldName,
        boolean enabled,
        int size,
        int target,
        /* Background searches currently running for this world. */
        int searching,
        long totalFound,
        long totalServed,
        long totalFailedSearches,
        long totalExpired,
        double avgAttemptsPerFind,
        double avgMillisPerFind,
        /* Chunks currently held by plugin tickets for this world. */
        int ticketedChunks,
        boolean paused,
        String pauseReason
) {
    public double fillRatio() {
        return target <= 0 ? 1.0 : Math.min(1.0, (double) size / target);
    }
}

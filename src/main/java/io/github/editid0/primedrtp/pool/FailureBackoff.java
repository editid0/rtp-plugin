package io.github.editid0.primedrtp.pool;

/**
 * Pure exponential backoff with a throttled "keeps failing" warning, used per world so an impossible configuration
 * (e.g. a biome whitelist that matches nothing) cannot burn CPU with endless background searches.
 * Not thread-safe; callers synchronise (it lives inside {@link WorldPool}).
 */
final class FailureBackoff {

    static final long BASE_DELAY_MS = 500;
    static final long MAX_DELAY_MS = 30_000;
    /** Consecutive failures before the first warning is logged. */
    static final int WARN_THRESHOLD = 20;
    /** Minimum time between two warnings for the same world. */
    static final long WARN_INTERVAL_MS = 5 * 60_000;

    private int consecutiveFailures;
    private long retryAt;
    private long lastWarnAt = Long.MIN_VALUE;

    /**
     * Records a failure at {@code now}.
     *
     * @return true when a warning should be logged now (threshold reached and not warned recently)
     */
    boolean recordFailure(long now) {
        consecutiveFailures++;
        retryAt = now + delayFor(consecutiveFailures);
        if (consecutiveFailures >= WARN_THRESHOLD
                && (lastWarnAt == Long.MIN_VALUE || now - lastWarnAt >= WARN_INTERVAL_MS)) {
            lastWarnAt = now;
            return true;
        }
        return false;
    }

    void recordSuccess() {
        consecutiveFailures = 0;
        retryAt = 0;
    }

    /** Clears the backoff (e.g. settings changed or an admin forced a refill) but keeps the warn throttle. */
    void reset() {
        recordSuccess();
    }

    boolean canAttempt(long now) {
        return now >= retryAt;
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    long retryAt() {
        return retryAt;
    }

    /** Delay after the n-th consecutive failure: 0.5s, 1s, 2s, ... capped at 30s. */
    static long delayFor(int failures) {
        if (failures <= 0) {
            return 0;
        }
        int shift = Math.min(failures - 1, 20);
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS << shift);
    }
}

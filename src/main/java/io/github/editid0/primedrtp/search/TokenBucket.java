package io.github.editid0.primedrtp.search;

/**
 * Thread-safe token bucket used to rate-limit BACKGROUND chunk loads.
 * <p>
 * Rate and capacity are passed on every call (instead of being fixed at construction) so a config reload takes effect
 * immediately without losing the bucket's state. Time is injected as {@code nowNanos} so the logic is unit-testable.
 */
final class TokenBucket {

    private double tokens;
    private long lastRefillNanos;
    private boolean initialised;

    /**
     * Tries to take one token.
     *
     * @param nowNanos       current {@link System#nanoTime()}
     * @param ratePerSecond  refill rate; {@code <= 0} means unlimited (always succeeds)
     * @param capacity       maximum stored tokens (burst size), clamped to at least 1
     * @return true if a token was taken
     */
    synchronized boolean tryAcquire(long nowNanos, double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0) {
            return true;
        }
        double cap = Math.max(1.0, capacity);
        if (!initialised) {
            initialised = true;
            lastRefillNanos = nowNanos;
            tokens = cap;
        } else {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(cap, tokens + elapsed * ratePerSecond / 1_000_000_000.0);
                lastRefillNanos = nowNanos;
            }
            tokens = Math.min(tokens, cap);
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Suggested burst capacity for a rate: a quarter second worth of tokens, at least 1. */
    static double defaultCapacity(double ratePerSecond) {
        return Math.max(1.0, Math.ceil(ratePerSecond / 4.0));
    }

    synchronized double available() {
        return tokens;
    }
}

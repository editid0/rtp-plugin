package io.github.editid0.swiftrtp.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    void burstThenRefill() {
        TokenBucket b = new TokenBucket();
        long t = 1_000L;
        for (int i = 0; i < 5; i++) {
            assertTrue(b.tryAcquire(t, 20, 5));
        }
        assertFalse(b.tryAcquire(t, 20, 5));
        // 20/s -> one token every 50ms
        assertFalse(b.tryAcquire(t + SEC / 40, 20, 5));
        assertTrue(b.tryAcquire(t + SEC / 20, 20, 5));
        assertFalse(b.tryAcquire(t + SEC / 20, 20, 5));
    }

    @Test
    void sustainedRateMatchesConfig() {
        TokenBucket b = new TokenBucket();
        int acquired = 0;
        // Poll every tick (50ms) for 10 seconds at 7/s, capacity 2.
        for (long t = 0; t <= 10 * SEC; t += SEC / 20) {
            while (b.tryAcquire(t, 7, 2)) {
                acquired++;
            }
        }
        assertEquals(72, acquired, 2); // 2 initial burst + 70
    }

    @Test
    void capacityCapsIdleAccumulation() {
        TokenBucket b = new TokenBucket();
        assertTrue(b.tryAcquire(0, 10, 3));
        int n = 0;
        while (b.tryAcquire(100 * SEC, 10, 3)) {
            n++;
        }
        assertEquals(3, n);
    }

    @Test
    void unlimitedRate() {
        TokenBucket b = new TokenBucket();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(b.tryAcquire(0, 0, 1));
        }
    }

    @Test
    void defaultCapacity() {
        assertEquals(1.0, TokenBucket.defaultCapacity(1));
        assertEquals(5.0, TokenBucket.defaultCapacity(20));
        assertEquals(1.0, TokenBucket.defaultCapacity(0));
    }
}

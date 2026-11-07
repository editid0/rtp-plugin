package io.github.editid0.swiftrtp.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureBackoffTest {

    @Test
    void delayDoublesAndCaps() {
        assertEquals(0, FailureBackoff.delayFor(0));
        assertEquals(500, FailureBackoff.delayFor(1));
        assertEquals(1000, FailureBackoff.delayFor(2));
        assertEquals(2000, FailureBackoff.delayFor(3));
        assertEquals(FailureBackoff.MAX_DELAY_MS, FailureBackoff.delayFor(10));
        assertEquals(FailureBackoff.MAX_DELAY_MS, FailureBackoff.delayFor(Integer.MAX_VALUE));
    }

    @Test
    void blocksUntilRetryAndResetsOnSuccess() {
        FailureBackoff b = new FailureBackoff();
        assertTrue(b.canAttempt(0));
        b.recordFailure(1000);
        assertFalse(b.canAttempt(1499));
        assertTrue(b.canAttempt(1500));
        b.recordFailure(1500);
        assertFalse(b.canAttempt(2499));
        b.recordSuccess();
        assertEquals(0, b.consecutiveFailures());
        assertTrue(b.canAttempt(1600));
    }

    @Test
    void warnsAtThresholdThenThrottled() {
        FailureBackoff b = new FailureBackoff();
        long now = 0;
        for (int i = 1; i < FailureBackoff.WARN_THRESHOLD; i++) {
            assertFalse(b.recordFailure(now += 10));
        }
        assertTrue(b.recordFailure(now += 10));
        assertFalse(b.recordFailure(now += 10));
        assertFalse(b.recordFailure(now + FailureBackoff.WARN_INTERVAL_MS - 100));
        assertTrue(b.recordFailure(now + FailureBackoff.WARN_INTERVAL_MS + 100));
    }
}

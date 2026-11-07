package io.github.editid0.swiftrtp.pool;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TicketRefCounterTest {

    private final UUID w1 = UUID.randomUUID();
    private final UUID w2 = UUID.randomUUID();

    @Test
    void realTicketOnlyOnFirstAndLastReference() {
        TicketRefCounter c = new TicketRefCounter();
        long k = ChunkArea.key(1, 1);
        assertTrue(c.increment(w1, k));
        assertFalse(c.increment(w1, k));
        assertFalse(c.increment(w1, k));
        assertEquals(3, c.count(w1, k));
        assertFalse(c.decrement(w1, k));
        assertFalse(c.decrement(w1, k));
        assertTrue(c.decrement(w1, k));
        assertEquals(0, c.count(w1, k));
        assertEquals(0, c.totalChunks());
    }

    @Test
    void doubleReleaseNeverGoesNegative() {
        TicketRefCounter c = new TicketRefCounter();
        long k = ChunkArea.key(0, 0);
        assertFalse(c.decrement(w1, k));
        c.increment(w1, k);
        assertTrue(c.decrement(w1, k));
        assertFalse(c.decrement(w1, k));
        assertTrue(c.increment(w1, k), "fresh first reference after full release");
    }

    @Test
    void worldsAreIndependentAndClearable() {
        TicketRefCounter c = new TicketRefCounter();
        c.increment(w1, ChunkArea.key(0, 0));
        c.increment(w1, ChunkArea.key(0, 1));
        c.increment(w2, ChunkArea.key(0, 0));
        assertEquals(2, c.chunkCount(w1));
        assertEquals(1, c.chunkCount(w2));
        assertEquals(3, c.totalChunks());
        assertEquals(2, c.clearWorld(w1));
        assertEquals(0, c.chunkCount(w1));
        assertEquals(1, c.totalChunks());
        c.clearAll();
        assertEquals(0, c.totalChunks());
    }

    @Test
    void overlappingEntriesShareTickets() {
        // Two entries with keep-loaded radius 1 whose squares overlap in a 2x3 strip.
        RecordingRefs refs = new RecordingRefs();
        long[] a = ChunkArea.square(0, 0, 1);
        long[] b = ChunkArea.square(1, 0, 1);
        for (long k : a) refs.acquire(w1, ChunkArea.keyX(k), ChunkArea.keyZ(k));
        for (long k : b) refs.acquire(w1, ChunkArea.keyX(k), ChunkArea.keyZ(k));
        assertEquals(12, refs.realAdds);
        refs.releaseAll(w1, a);
        assertEquals(3, refs.realRemoves, "only chunks exclusive to A lose their ticket");
        refs.releaseAll(w1, b);
        assertEquals(12, refs.realRemoves);
        assertEquals(0, refs.counter.totalChunks());
    }
}

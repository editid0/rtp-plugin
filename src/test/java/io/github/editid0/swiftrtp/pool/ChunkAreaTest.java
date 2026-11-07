package io.github.editid0.swiftrtp.pool;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChunkAreaTest {

    @Test
    void keyRoundTripsNegativeAndExtremeCoordinates() {
        int[][] coords = {{0, 0}, {-1, -1}, {1875000, -1875000}, {Integer.MIN_VALUE, Integer.MAX_VALUE}};
        for (int[] c : coords) {
            long k = ChunkArea.key(c[0], c[1]);
            assertEquals(c[0], ChunkArea.keyX(k));
            assertEquals(c[1], ChunkArea.keyZ(k));
        }
        assertNotEquals(ChunkArea.key(1, 2), ChunkArea.key(2, 1));
    }

    @Test
    void squareCoversRadiusCentreFirstNearestFirst() {
        long[] keys = ChunkArea.square(-5, 7, 2);
        assertEquals(25, keys.length);
        assertEquals(ChunkArea.key(-5, 7), keys[0]);
        Set<Long> unique = new HashSet<>();
        long lastDist = -1;
        for (long k : keys) {
            assertTrue(unique.add(k));
            assertTrue(ChunkArea.chebyshev(k, -5, 7) <= 2);
            long dx = ChunkArea.keyX(k) + 5, dz = ChunkArea.keyZ(k) - 7;
            long d = dx * dx + dz * dz;
            assertTrue(d >= lastDist, "sorted by distance");
            lastDist = d;
        }
    }

    @Test
    void negativeRadiusIsSingleChunk() {
        assertArrayEquals(new long[]{ChunkArea.key(3, 4)}, ChunkArea.square(3, 4, -1));
        assertEquals(1, ChunkArea.square(3, 4, 0).length);
    }
}

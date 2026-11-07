package io.github.editid0.primedrtp.pool;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Pure chunk-coordinate helpers: packing chunk coordinates into a {@code long} key and enumerating the square of
 * chunks around a centre, nearest first (so pregeneration starts at the destination and spirals outward).
 */
final class ChunkArea {

    private ChunkArea() {}

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    /**
     * All chunk keys with {@code max(|dx|,|dz|) <= radius} around the centre, ordered by distance from the centre
     * (centre first). A negative radius is treated as 0.
     */
    static long[] square(int centerX, int centerZ, int radius) {
        int r = Math.max(0, radius);
        int side = 2 * r + 1;
        Long[] keys = new Long[side * side];
        int i = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                keys[i++] = key(centerX + dx, centerZ + dz);
            }
        }
        Arrays.sort(keys, Comparator.comparingLong(k -> distSq(k, centerX, centerZ)));
        long[] out = new long[keys.length];
        for (int j = 0; j < keys.length; j++) {
            out[j] = keys[j];
        }
        return out;
    }

    /** Chebyshev (square) distance of a key from a centre, matching {@link #square}. */
    static int chebyshev(long key, int centerX, int centerZ) {
        return Math.max(Math.abs(keyX(key) - centerX), Math.abs(keyZ(key) - centerZ));
    }

    private static long distSq(long key, int cx, int cz) {
        long dx = keyX(key) - cx;
        long dz = keyZ(key) - cz;
        return dx * dx + dz * dz;
    }
}

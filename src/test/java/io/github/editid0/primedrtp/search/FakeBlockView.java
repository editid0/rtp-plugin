package io.github.editid0.primedrtp.search;

import java.util.HashMap;
import java.util.Map;

/**
 * Character-grid {@link BlockView} for tests. Unset blocks are air.
 * <pre>
 *  '.' air         '#' stone (solid)      'b' bedrock (solid)   'm' magma (solid, unsafe floor)
 *  'w' water       'l' lava               'L' leaves (solid, ignored by surfaceY)
 *  'g' grass plant (passable)             'f' fire (passable, unsafe nearby)
 *  's' waterlogged slab (solid + liquid)  'c' cactus (solid, unsafe floor + nearby)
 * </pre>
 */
final class FakeBlockView implements BlockView {

    private final int minY;
    private final int maxY;
    private final Map<Long, Character> blocks = new HashMap<>();

    FakeBlockView(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    FakeBlockView set(int x, int y, int z, char c) {
        blocks.put(key(x, y, z), c);
        return this;
    }

    /** Fills y in [fromY, toY] of column (x, z). */
    FakeBlockView fill(int x, int z, int fromY, int toY, char c) {
        for (int y = fromY; y <= toY; y++) {
            set(x, y, z, c);
        }
        return this;
    }

    /** Fills a horizontal square layer (x±r, z±r) for y in [fromY, toY]. */
    FakeBlockView layer(int cx, int cz, int r, int fromY, int toY, char c) {
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                fill(x, z, fromY, toY, c);
            }
        }
        return this;
    }

    char at(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return '.';
        }
        return blocks.getOrDefault(key(x, y, z), '.');
    }

    private static boolean motionBlocking(char c) {
        return c == '#' || c == 'b' || c == 'm' || c == 'w' || c == 'l' || c == 's' || c == 'c' || c == 'L';
    }

    @Override public int minY() { return minY; }
    @Override public int maxY() { return maxY; }

    @Override
    public int surfaceY(int x, int z) {
        for (int y = maxY; y >= minY; y--) {
            char c = at(x, y, z);
            if (motionBlocking(c) && c != 'L') {
                return y;
            }
        }
        return minY - 1;
    }

    @Override
    public int skyY(int x, int z) {
        for (int y = maxY; y >= minY; y--) {
            if (motionBlocking(at(x, y, z))) {
                return y;
            }
        }
        return minY - 1;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        char c = at(x, y, z);
        return c == '#' || c == 'b' || c == 'm' || c == 's' || c == 'c' || c == 'L';
    }

    @Override
    public boolean passable(int x, int y, int z) {
        char c = at(x, y, z);
        return c == '.' || c == 'g' || c == 'f' || c == 'w' || c == 'l';
    }

    @Override
    public boolean liquid(int x, int y, int z) {
        char c = at(x, y, z);
        return c == 'w' || c == 'l' || c == 's';
    }

    @Override
    public boolean lava(int x, int y, int z) {
        return at(x, y, z) == 'l';
    }

    @Override
    public boolean unsafeFloor(int x, int y, int z) {
        char c = at(x, y, z);
        return c == 'm' || c == 'c';
    }

    @Override
    public boolean unsafeNearby(int x, int y, int z) {
        char c = at(x, y, z);
        return c == 'f' || c == 'c';
    }
}

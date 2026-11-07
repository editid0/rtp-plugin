package io.github.editid0.swiftrtp.command;

import io.github.editid0.swiftrtp.pool.PoolStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoolBarTest {

    private static PoolStats stats(boolean enabled, int size, int target, boolean paused) {
        return new PoolStats("world", enabled, size, target, 0, 0, 0, 0, 0, 0, 0, 0, paused, paused ? "mspt" : "");
    }

    @Test
    void filledCellsRoundsAndClamps() {
        assertEquals(0, PoolBar.filledCells(0, 10));
        assertEquals(0, PoolBar.filledCells(-1, 10));
        assertEquals(0, PoolBar.filledCells(Double.NaN, 10));
        assertEquals(10, PoolBar.filledCells(1, 10));
        assertEquals(10, PoolBar.filledCells(2, 10));
        assertEquals(5, PoolBar.filledCells(0.5, 10));
        assertEquals(1, PoolBar.filledCells(0.01, 10), "non-empty pool never looks empty");
        assertEquals(9, PoolBar.filledCells(0.99, 10), "non-full pool never looks full");
        assertThrows(IllegalArgumentException.class, () -> PoolBar.filledCells(0.5, 0));
    }

    @Test
    void colourThresholds() {
        assertEquals("red", PoolBar.colorFor(0.2));
        assertEquals("yellow", PoolBar.colorFor(0.35));
        assertEquals("yellow", PoolBar.colorFor(0.7));
        assertEquals("green", PoolBar.colorFor(0.75));
        assertEquals("green", PoolBar.colorFor(1));
    }

    @Test
    void renderProducesTenCells() {
        assertEquals("<green>■■■■■■■■■■</green>", PoolBar.render(1.0));
        assertEquals("<dark_gray>■■■■■■■■■■</dark_gray>", PoolBar.render(0.0));
        assertEquals("<yellow>■■■■■</yellow><dark_gray>■■■■■</dark_gray>", PoolBar.render(0.5));
        String bar = PoolBar.render(0.3);
        assertEquals(10, bar.replaceAll("<[^>]+>", "").length());
    }

    @Test
    void statusPriority() {
        assertEquals(PoolBar.STATUS_DISABLED, PoolBar.statusKey(stats(false, 0, 10, true)));
        assertEquals(PoolBar.STATUS_PAUSED, PoolBar.statusKey(stats(true, 10, 10, true)));
        assertEquals(PoolBar.STATUS_FILLING, PoolBar.statusKey(stats(true, 3, 10, false)));
        assertEquals(PoolBar.STATUS_OK, PoolBar.statusKey(stats(true, 10, 10, false)));
        assertEquals(PoolBar.STATUS_OK, PoolBar.statusKey(stats(true, 0, 0, false)));
    }
}

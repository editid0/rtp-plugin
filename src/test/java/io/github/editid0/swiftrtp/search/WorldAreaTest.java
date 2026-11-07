package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.config.WorldSettings.AreaSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.CenterMode;
import io.github.editid0.swiftrtp.config.WorldSettings.Shape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAreaTest {

    private static AreaSettings area(CenterMode mode, Shape shape, int min, int max, boolean border, int padding) {
        return new AreaSettings(mode, 1000, 2000, shape, min, max, border, padding);
    }

    @Test
    void centreModes() {
        WorldArea spawn = WorldArea.resolve(area(CenterMode.WORLD_SPAWN, Shape.SQUARE, 0, 100, false, 0),
                -10.7, 20.2, 500, 500, 1e7);
        assertNotNull(spawn);
        assertEquals(-11, spawn.centerX());
        assertEquals(20, spawn.centerZ());
        WorldArea fixed = WorldArea.resolve(area(CenterMode.FIXED, Shape.SQUARE, 0, 100, false, 0), 0, 0, 0, 0, 1e7);
        assertEquals(1000, fixed.centerX());
        assertEquals(2000, fixed.centerZ());
        WorldArea border = WorldArea.resolve(area(CenterMode.WORLD_BORDER, Shape.SQUARE, 0, 100, false, 0),
                0, 0, 300.5, -40.5, 1e7);
        assertEquals(300, border.centerX());
        assertEquals(-41, border.centerZ());
    }

    @Test
    void clampToBorderWhenCentred() {
        // Border size 2000 centred at 0.5,0.5 (vanilla block-centre), padding 50 -> limit 950.
        WorldArea a = WorldArea.resolve(area(CenterMode.WORLD_BORDER, Shape.SQUARE, 0, 5000, true, 50),
                0, 0, 0.5, 0.5, 1000);
        assertNotNull(a);
        assertEquals(950, a.maxRadius());
        assertTrue(a.insideBorder(950, -950));
        assertFalse(a.insideBorder(951, 0));
        WorldArea c = WorldArea.resolve(area(CenterMode.WORLD_BORDER, Shape.CIRCLE, 0, 5000, true, 50),
                0, 0, 0.5, 0.5, 1000);
        assertEquals((int) Math.floor(950 * Math.sqrt(2)), c.maxRadius());
    }

    @Test
    void noBorderNoClamp() {
        WorldArea a = WorldArea.resolve(area(CenterMode.FIXED, Shape.SQUARE, 0, 5000, false, 50), 0, 0, 0, 0, 10);
        assertEquals(5000, a.maxRadius());
        assertTrue(a.insideBorder(1_000_000, 0));
    }

    @Test
    void emptyAreas() {
        assertNull(WorldArea.resolve(area(CenterMode.FIXED, Shape.SQUARE, 200, 100, false, 0), 0, 0, 0, 0, 1e7));
        // Clamped below min radius.
        assertNull(WorldArea.resolve(area(CenterMode.WORLD_BORDER, Shape.SQUARE, 2000, 5000, true, 0),
                0, 0, 0.5, 0.5, 1000));
        // Padding eats the whole border.
        assertNull(WorldArea.resolve(area(CenterMode.WORLD_BORDER, Shape.SQUARE, 0, 5000, true, 100),
                0, 0, 0.5, 0.5, 50));
    }
}

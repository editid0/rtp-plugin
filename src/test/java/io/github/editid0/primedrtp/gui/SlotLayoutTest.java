package io.github.editid0.primedrtp.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotLayoutTest {

    @Test
    void parsesSlots() {
        assertEquals(13, SlotLayout.parse(13));
        assertEquals(13, SlotLayout.parse("13"));
        assertEquals(13, SlotLayout.parse("2:5"));
        assertEquals(0, SlotLayout.parse("1,1"));
        assertEquals(53, SlotLayout.parse("6:9"));
        assertNull(SlotLayout.parse(null));
        assertNull(SlotLayout.parse("auto"));
        assertNull(SlotLayout.parse("0:1"));
        assertNull(SlotLayout.parse("1:10"));
        assertNull(SlotLayout.parse("x"));
    }

    @Test
    void bounds() {
        assertTrue(SlotLayout.inBounds(26, 3));
        assertFalse(SlotLayout.inBounds(27, 3));
        assertFalse(SlotLayout.inBounds(-1, 3));
        assertEquals(1, SlotLayout.clampRows(0));
        assertEquals(6, SlotLayout.clampRows(9));
    }

    @Test
    void autoPlaceCentresInMiddleRow() {
        assertEquals(List.of(11, 13, 15), SlotLayout.autoPlace(3, 3, Set.of()));
        assertEquals(List.of(13), SlotLayout.autoPlace(3, 1, Set.of()));
        assertEquals(List.of(4), SlotLayout.autoPlace(1, 1, Set.of()));
        // 6 items don't fit with gaps: contiguous, centred.
        assertEquals(List.of(19, 20, 21, 22, 23, 24), SlotLayout.autoPlace(5, 6, Set.of()));
    }

    @Test
    void autoPlaceFallsBackToFreeSlots() {
        assertEquals(List.of(0, 1, 2), SlotLayout.autoPlace(3, 3, Set.of(13)));
        assertEquals(List.of(), SlotLayout.autoPlace(1, 0, Set.of()));
        assertEquals(List.of(8), SlotLayout.autoPlace(1, 2, Set.of(0, 1, 2, 3, 4, 5, 6, 7)));
    }
}

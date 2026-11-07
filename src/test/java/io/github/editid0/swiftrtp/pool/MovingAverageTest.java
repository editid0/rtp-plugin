package io.github.editid0.swiftrtp.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovingAverageTest {

    @Test
    void firstSampleInitialisesThenSmooths() {
        MovingAverage m = new MovingAverage(0.5);
        assertEquals(0.0, m.value());
        m.add(10);
        assertEquals(10.0, m.value(), 1e-9);
        m.add(20);
        assertEquals(15.0, m.value(), 1e-9);
        m.add(15);
        assertEquals(15.0, m.value(), 1e-9);
    }

    @Test
    void rejectsBadAlpha() {
        assertThrows(IllegalArgumentException.class, () -> new MovingAverage(0));
        assertThrows(IllegalArgumentException.class, () -> new MovingAverage(1.5));
        assertThrows(IllegalArgumentException.class, () -> new MovingAverage(Double.NaN));
    }
}

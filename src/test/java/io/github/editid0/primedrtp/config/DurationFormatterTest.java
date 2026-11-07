package io.github.editid0.primedrtp.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatterTest {

    private static final DurationFormatter.Units U = DurationFormatter.Units.DEFAULT;

    private static String f(Duration d) {
        return DurationFormatter.format(d, U);
    }

    @Test
    void mixedUnits() {
        assertEquals("1h 2m 5s", f(Duration.ofSeconds(3725)));
        assertEquals("1d 1h 1m 1s", f(Duration.ofSeconds(86_400 + 3_600 + 61)));
    }

    @Test
    void zeroComponentsOmitted() {
        assertEquals("1h", f(Duration.ofHours(1)));
        assertEquals("2d 5s", f(Duration.ofDays(2).plusSeconds(5)));
        assertEquals("3s", f(Duration.ofSeconds(3)));
        assertEquals("1m", f(Duration.ofSeconds(60)));
    }

    @Test
    void zeroAndNegativeAreNow() {
        assertEquals("now", f(Duration.ZERO));
        assertEquals("now", f(Duration.ofSeconds(-4)));
    }

    @Test
    void subSecondRoundsUp() {
        assertEquals("1s", f(Duration.ofMillis(1)));
        assertEquals("1s", f(Duration.ofMillis(999)));
        assertEquals("1s", f(Duration.ofNanos(5)));
        assertEquals("2s", f(Duration.ofMillis(1001)));
        assertEquals("1m", f(Duration.ofMillis(59_001)));
    }

    @Test
    void customUnits() {
        var units = new DurationFormatter.Units(" days", " hours", " minutes", " seconds", ", ", "right now");
        assertEquals("1 hours, 2 minutes, 5 seconds", DurationFormatter.format(Duration.ofSeconds(3725), units));
        assertEquals("right now", DurationFormatter.format(Duration.ZERO, units));
    }
}

package io.github.editid0.swiftrtp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {

    @Test
    void unitSuffixes() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30s"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        assertEquals(Duration.ofHours(1).plusMinutes(30), DurationParser.parse("1h30m"));
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d"));
        assertEquals(Duration.ofMillis(500), DurationParser.parse("500ms"));
        assertEquals(Duration.ofDays(7), DurationParser.parse("1w"));
    }

    @Test
    void combinationsAndSpacing() {
        assertEquals(Duration.ofHours(1).plusMinutes(30), DurationParser.parse("1h 30m"));
        assertEquals(Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4).plusMillis(5),
                DurationParser.parse("1d2h3m4s5ms"));
        assertEquals(Duration.ofSeconds(10), DurationParser.parse("10 seconds"));
        assertEquals(Duration.ofMinutes(2), DurationParser.parse("2 MINUTES"));
        assertEquals(Duration.ofHours(1).plusMinutes(5), DurationParser.parse("1h, 5m"));
        assertEquals(Duration.ofSeconds(5), DurationParser.parse("  5S  "));
    }

    @Test
    void decimals() {
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1.5h"));
        assertEquals(Duration.ofMillis(1500), DurationParser.parse("1.5"));
        assertEquals(Duration.ofMillis(250), DurationParser.parse(0.25));
    }

    @Test
    void plainNumbersAreSeconds() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse(30));
        assertEquals(Duration.ofSeconds(30), DurationParser.parse(30L));
        assertEquals(Duration.ofSeconds(45), DurationParser.parse("45"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "none", "NONE", "off", "never", "disabled", "", "  ", "0s", "0ms"})
    void zeroForms(String s) {
        assertEquals(Duration.ZERO, DurationParser.parse(s));
    }

    @Test
    void zeroValues() {
        assertEquals(Duration.ZERO, DurationParser.parse(0));
        assertEquals(Duration.ZERO, DurationParser.parse(false));
    }

    @Test
    void negative() {
        assertEquals(Duration.ofSeconds(-5), DurationParser.parse("-5s"));
        assertEquals(Duration.ofSeconds(-5), DurationParser.parse(-5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "5x", "1h 30", "h", "1..5s", "--5s", "5s!", "s5"})
    void invalid(String s) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(s));
    }

    @Test
    void nullIsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse((Object) null));
    }
}

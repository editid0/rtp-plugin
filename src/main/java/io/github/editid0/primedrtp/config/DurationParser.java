package io.github.editid0.primedrtp.config;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly durations used throughout config.yml.
 * <p>
 * Accepted forms (case-insensitive, whitespace between parts allowed):
 * <ul>
 *   <li>plain numbers (YAML int/float or numeric string) = seconds: {@code 30}, {@code "1.5"}</li>
 *   <li>unit sequences: {@code 500ms}, {@code 30s}, {@code 5m}, {@code 1h30m}, {@code 2d}, {@code 1w}, {@code 1h 30m}</li>
 *   <li>long unit names: {@code 10 seconds}, {@code 2 minutes}, {@code 1 hour}, {@code 3 days}</li>
 *   <li>{@code 0}, {@code "0"}, {@code none}, {@code off}, {@code never}, {@code disabled}, {@code ""} = {@link Duration#ZERO}</li>
 *   <li>a leading {@code -} yields a negative duration (callers decide whether to clamp)</li>
 * </ul>
 * Decimal values are allowed per unit ({@code 1.5h}); results are truncated to whole milliseconds.
 */
public final class DurationParser {

    private static final Set<String> ZERO_WORDS = Set.of("", "none", "off", "never", "disabled", "no", "false");
    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-z]*)");

    private DurationParser() {}

    /**
     * Parses a YAML value into a duration.
     *
     * @throws IllegalArgumentException with a human-readable reason when the value is not a duration
     */
    public static Duration parse(@Nullable Object value) {
        if (value == null) {
            throw new IllegalArgumentException("missing value");
        }
        if (value instanceof Duration d) {
            return d;
        }
        if (value instanceof Number n) {
            return ofSeconds(new BigDecimal(n.toString()));
        }
        if (value instanceof Boolean b && !b) {
            return Duration.ZERO;
        }
        return parse(value.toString());
    }

    /** Parses a textual duration. */
    public static Duration parse(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (ZERO_WORDS.contains(s)) {
            return Duration.ZERO;
        }
        boolean negative = false;
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).trim();
        } else if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("'" + text + "' is not a duration");
        }
        // Plain number = seconds.
        if (s.matches("\\d+(?:\\.\\d+)?")) {
            Duration d = ofSeconds(new BigDecimal(s));
            return negative ? d.negated() : d;
        }
        Matcher m = PART.matcher(s);
        int pos = 0;
        Duration total = Duration.ZERO;
        boolean any = false;
        while (pos < s.length()) {
            // skip separators between parts
            char c = s.charAt(pos);
            if (c == ' ' || c == ',' || c == '\t') {
                pos++;
                continue;
            }
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("'" + text + "' is not a duration (expected e.g. 30s, 5m, 1h30m)");
            }
            BigDecimal amount = new BigDecimal(m.group(1));
            String unit = m.group(2);
            if (unit.isEmpty()) {
                throw new IllegalArgumentException("'" + text + "': missing unit after " + m.group(1)
                        + " (use ms, s, m, h, d or w)");
            }
            long millisPerUnit = unitMillis(unit);
            if (millisPerUnit < 0) {
                throw new IllegalArgumentException("'" + text + "': unknown time unit '" + unit
                        + "' (use ms, s, m, h, d or w)");
            }
            total = total.plusMillis(amount.multiply(BigDecimal.valueOf(millisPerUnit))
                    .setScale(0, RoundingMode.DOWN).longValueExact());
            any = true;
            pos = m.end();
        }
        if (!any) {
            throw new IllegalArgumentException("'" + text + "' is not a duration");
        }
        return negative ? total.negated() : total;
    }

    private static Duration ofSeconds(BigDecimal seconds) {
        return Duration.ofMillis(seconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.DOWN).longValueExact());
    }

    /** Milliseconds per unit, or -1 if unknown. */
    private static long unitMillis(String unit) {
        return switch (unit) {
            case "ms", "milli", "millis", "millisecond", "milliseconds" -> 1L;
            case "s", "sec", "secs", "second", "seconds" -> 1_000L;
            case "m", "min", "mins", "minute", "minutes" -> 60_000L;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "week", "weeks" -> 604_800_000L;
            default -> -1L;
        };
    }
}

package io.github.editid0.primedrtp.config;

import java.time.Duration;

/**
 * Pure helper that renders durations like {@code 1h 2m 5s} using configurable unit suffixes (messages.yml
 * {@code time.*}).
 * <p>
 * Rules: zero components are omitted; sub-second remainders round <em>up</em> to the next second (a 300 ms
 * cooldown shows as {@code 1s}, never "now"); zero or negative durations render as {@link Units#now()}.
 */
public final class DurationFormatter {

    /** Unit suffixes and joiner. */
    public record Units(String days, String hours, String minutes, String seconds, String separator, String now) {
        public static final Units DEFAULT = new Units("d", "h", "m", "s", " ", "now");
    }

    private DurationFormatter() {}

    public static String format(Duration duration, Units units) {
        if (duration.isNegative() || duration.isZero()) {
            return units.now();
        }
        long millis = duration.toMillis();
        if (millis == 0) {
            // positive but < 1 ms (nanos only) still rounds up to one second
            millis = 1;
        }
        long total = Math.ceilDiv(millis, 1000L);
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long seconds = total % 60;

        StringBuilder sb = new StringBuilder();
        append(sb, days, units.days(), units.separator());
        append(sb, hours, units.hours(), units.separator());
        append(sb, minutes, units.minutes(), units.separator());
        append(sb, seconds, units.seconds(), units.separator());
        return sb.toString();
    }

    private static void append(StringBuilder sb, long value, String unit, String separator) {
        if (value <= 0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(separator);
        }
        sb.append(value).append(unit);
    }
}

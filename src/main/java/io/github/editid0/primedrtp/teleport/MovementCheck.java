package io.github.editid0.primedrtp.teleport;

/** Pure warmup movement test (head rotation is never considered). */
public final class MovementCheck {

    private MovementCheck() {}

    /** Vertical drift always allowed, so jumping in place (peak ~1.25 blocks) never cancels a warmup. */
    static final double MIN_VERTICAL_TOLERANCE = 1.5;

    /**
     * Horizontal distance is compared against {@code tolerance}; vertical drift against
     * {@code max(tolerance, 1.5)} so jumping in place is allowed but falling/climbing away is not.
     *
     * @return true if the player moved too far (a negative tolerance is treated as 0, i.e. any horizontal change
     *         counts)
     */
    public static boolean exceeds(double x1, double y1, double z1, double x2, double y2, double z2, double tolerance) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double tol = Math.max(0.0, tolerance);
        // Tiny epsilon so floating-point jitter of a stationary player never cancels a 0-tolerance warmup.
        if (dx * dx + dz * dz > tol * tol + 1.0E-6) {
            return true;
        }
        return Math.abs(dy) > Math.max(tol, MIN_VERTICAL_TOLERANCE) + 1.0E-3;
    }

    /** Seconds to display for the remaining ticks (rounded up, minimum 0). */
    public static int displaySeconds(long remainingTicks) {
        return remainingTicks <= 0 ? 0 : (int) ((remainingTicks + 19) / 20);
    }

    /** Converts a duration in millis to whole ticks, rounding up (0 for non-positive). */
    public static long toTicks(long millis) {
        return millis <= 0 ? 0 : (millis + 49) / 50;
    }
}

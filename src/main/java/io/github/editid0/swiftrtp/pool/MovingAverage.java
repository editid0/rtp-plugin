package io.github.editid0.swiftrtp.pool;

/**
 * Exponential moving average; the first sample initialises the value. Not thread-safe (guarded by its owner).
 */
final class MovingAverage {

    private final double alpha;
    private double value;
    private boolean initialised;

    MovingAverage(double alpha) {
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException("alpha must be in (0, 1]: " + alpha);
        }
        this.alpha = alpha;
    }

    void add(double sample) {
        if (!initialised) {
            value = sample;
            initialised = true;
        } else {
            value += alpha * (sample - value);
        }
    }

    /** 0 until the first sample. */
    double value() {
        return initialised ? value : 0.0;
    }
}

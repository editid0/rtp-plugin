package io.github.editid0.swiftrtp.command;

import io.github.editid0.swiftrtp.pool.PoolStats;

/**
 * Pure rendering helpers for {@code /rtp admin pool}: the coloured fill bar and the status message key.
 * Free of Bukkit statics so it can be unit-tested.
 */
public final class PoolBar {

    /** Default bar width in characters. */
    public static final int WIDTH = 10;

    static final String FILLED = "■";
    static final String EMPTY = "■";

    public static final String STATUS_OK = "admin.pool-status-ok";
    public static final String STATUS_FILLING = "admin.pool-status-filling";
    public static final String STATUS_PAUSED = "admin.pool-status-paused";
    public static final String STATUS_DISABLED = "admin.pool-status-disabled";

    private PoolBar() {}

    /**
     * Number of filled cells for a fill ratio. Rounds to the nearest cell, but never shows a completely empty bar
     * for a non-empty pool nor a completely full bar for a pool that is not full.
     *
     * @param ratio fill ratio, clamped to [0, 1]; NaN counts as 0
     * @param width bar width (&gt;= 1)
     */
    public static int filledCells(double ratio, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be >= 1");
        }
        if (Double.isNaN(ratio) || ratio <= 0) {
            return 0;
        }
        if (ratio >= 1) {
            return width;
        }
        int cells = (int) Math.round(ratio * width);
        return Math.clamp(cells, 1, width - 1);
    }

    /** Colour for the filled part: green when mostly full, yellow when half, red when low. */
    public static String colorFor(double ratio) {
        if (Double.isNaN(ratio) || ratio < 0.35) {
            return "red";
        }
        return ratio < 0.75 ? "yellow" : "green";
    }

    /**
     * MiniMessage markup of a {@code width}-character bar, e.g. {@code <green>■■■■■■■</green><dark_gray>■■■</dark_gray>}.
     * Intended for {@code Placeholder.parsed("bar", ...)}.
     */
    public static String render(double ratio, int width) {
        int filled = filledCells(ratio, width);
        StringBuilder sb = new StringBuilder(width + 40);
        if (filled > 0) {
            String color = colorFor(ratio);
            sb.append('<').append(color).append('>').append(FILLED.repeat(filled)).append("</").append(color).append('>');
        }
        if (filled < width) {
            sb.append("<dark_gray>").append(EMPTY.repeat(width - filled)).append("</dark_gray>");
        }
        return sb.toString();
    }

    public static String render(double ratio) {
        return render(ratio, WIDTH);
    }

    /** Message key describing the pool's state: disabled &gt; paused &gt; filling &gt; ok. */
    public static String statusKey(PoolStats stats) {
        if (!stats.enabled()) {
            return STATUS_DISABLED;
        }
        if (stats.paused()) {
            return STATUS_PAUSED;
        }
        if (stats.size() < stats.target()) {
            return STATUS_FILLING;
        }
        return STATUS_OK;
    }
}

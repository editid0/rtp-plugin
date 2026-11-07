package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.config.WorldSettings.AreaSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.Shape;
import org.jspecify.annotations.Nullable;

/**
 * Resolved sampling area of one search: centre, ring radii (already clamped to the world border) and the border
 * constraint used by the cheap pre-filter. Pure value; built from config + a {@link WorldSnapshot}-style set of
 * numbers so it can be unit tested. Centre per {@code CenterMode}: WORLD_SPAWN, FIXED or WORLD_BORDER.
 *
 * @param borderLimit max allowed {@code |coord + 0.5 − borderCentre|} on each axis (border half-size − padding);
 *                    only meaningful when {@code respectBorder}
 */
record WorldArea(Shape shape, int centerX, int centerZ, int minRadius, int maxRadius,
                 boolean respectBorder, double borderCenterX, double borderCenterZ, double borderLimit) {

    /**
     * Resolves the area, or returns null if it is empty (min &gt; max after clamping, or the padded border has no
     * room at all).
     */
    static @Nullable WorldArea resolve(AreaSettings a, double spawnX, double spawnZ,
                                       double borderCenterX, double borderCenterZ, double borderHalfSize) {
        int cx;
        int cz;
        switch (a.centerMode()) {
            case WORLD_SPAWN -> { cx = floor(spawnX); cz = floor(spawnZ); }
            case WORLD_BORDER -> { cx = floor(borderCenterX); cz = floor(borderCenterZ); }
            default -> { cx = a.centerX(); cz = a.centerZ(); }
        }
        int minR = Math.max(0, a.minRadius());
        long maxR = a.maxRadius();
        double limit = borderHalfSize - Math.max(0, a.borderPadding());
        if (a.respectWorldBorder()) {
            if (limit < 0.5) {
                return null;
            }
            // Farthest distance (in the shape's metric) from the centre to any point inside the padded border.
            // When centred on the border this is exactly the padded half-size for SQUARE; for CIRCLE it reaches the
            // border's corners. Points of the ring outside the border are rejected by the pre-filter.
            double offX = Math.abs(cx + 0.5 - borderCenterX);
            double offZ = Math.abs(cz + 0.5 - borderCenterZ);
            double reach = a.shape() == Shape.CIRCLE
                    ? Math.sqrt(sq(limit + offX) + sq(limit + offZ))
                    : limit + Math.max(offX, offZ);
            maxR = Math.min(maxR, (long) Math.floor(reach));
        }
        if (maxR < 0 || minR > maxR) {
            return null;
        }
        return new WorldArea(a.shape(), cx, cz, minR, (int) maxR, a.respectWorldBorder(),
                borderCenterX, borderCenterZ, limit);
    }

    /** True if a player standing at the centre of block column (x, z) is inside the padded border (or no border). */
    boolean insideBorder(int x, int z) {
        if (!respectBorder) {
            return true;
        }
        return Math.abs(x + 0.5 - borderCenterX) <= borderLimit && Math.abs(z + 0.5 - borderCenterZ) <= borderLimit;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static double sq(double v) {
        return v * v;
    }
}

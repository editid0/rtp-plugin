package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.config.WorldSettings.Shape;

import java.util.random.RandomGenerator;

/**
 * Pure random sampling of candidate block columns inside a ring around a centre.
 * <ul>
 *   <li>{@link Shape#SQUARE}: uniform over the square ring {@code max(|dx|,|dz|) ∈ [min, max]}, sampled directly
 *       (no rejection) by splitting the ring into four congruent "pinwheel" rectangles.</li>
 *   <li>{@link Shape#CIRCLE}: uniform <em>by area</em> over the annulus {@code sqrt(dx²+dz²) ∈ [min, max]} using
 *       {@code r = sqrt(u·(R²−r²)+r²)}.</li>
 * </ul>
 * Picked columns always lie in a chunk's interior (block offset 1..14 on both axes) so 3×3 neighbour checks never
 * cross into another chunk. Interior-ness is enforced by rejection (keeps the distribution exactly uniform over the
 * interior columns); for degenerate areas without interior columns it falls back to the nearest interior column.
 */
final class AreaSampler {

    /** Max rejection rounds before falling back to a clamped point. */
    static final int MAX_TRIES = 64;

    private AreaSampler() {}

    /** Returns a column packed with {@link SearchOptions#pack(int, int)}. Requires {@code 0 <= minR <= maxR}. */
    static long sample(RandomGenerator rnd, Shape shape, int centerX, int centerZ, int minR, int maxR) {
        if (minR < 0 || maxR < minR) {
            throw new IllegalArgumentException("invalid ring [" + minR + ", " + maxR + "]");
        }
        int x = centerX;
        int z = centerZ;
        for (int i = 0; i < MAX_TRIES; i++) {
            long offset = shape == Shape.CIRCLE ? circleOffset(rnd, minR, maxR) : squareOffset(rnd, minR, maxR);
            if (offset == Long.MIN_VALUE) {
                continue;
            }
            x = centerX + SearchOptions.unpackX(offset);
            z = centerZ + SearchOptions.unpackZ(offset);
            if (isInterior(x) && isInterior(z)) {
                return SearchOptions.pack(x, z);
            }
        }
        // Degenerate area (e.g. radius 0 on a chunk edge): nudge into the interior.
        return SearchOptions.pack(toInterior(x), toInterior(z));
    }

    /** Uniform offset in the square ring. */
    static long squareOffset(RandomGenerator rnd, int r, int bigR) {
        // Four rectangles of size (R-r) x (R+r), each a 90° rotation of the first:
        //   x ∈ [r, R], z ∈ [-r, R]
        double a = r + rnd.nextDouble() * (bigR - r);
        double b = -r + rnd.nextDouble() * (bigR + r);
        double dx;
        double dz;
        switch (rnd.nextInt(4)) {
            case 0 -> { dx = a; dz = b; }
            case 1 -> { dx = -b; dz = a; }
            case 2 -> { dx = -a; dz = -b; }
            default -> { dx = b; dz = -a; }
        }
        // Rounding preserves the integer Chebyshev bounds (proved in tests): |round(v)| ∈ [r, R] when |v| ∈ [r, R].
        return SearchOptions.pack((int) Math.round(dx), (int) Math.round(dz));
    }

    /** Uniform-by-area offset in the annulus, or {@link Long#MIN_VALUE} if rounding left the ring (retry). */
    static long circleOffset(RandomGenerator rnd, int r, int bigR) {
        double r2 = (double) r * r;
        double bigR2 = (double) bigR * bigR;
        double radius = Math.sqrt(rnd.nextDouble() * (bigR2 - r2) + r2);
        double theta = rnd.nextDouble() * Math.PI * 2;
        int dx = (int) Math.round(radius * Math.cos(theta));
        int dz = (int) Math.round(radius * Math.sin(theta));
        double d2 = (double) dx * dx + (double) dz * dz;
        if (d2 < r2 || d2 > bigR2) {
            return Long.MIN_VALUE;
        }
        return SearchOptions.pack(dx, dz);
    }

    static boolean isInterior(int blockCoord) {
        int local = blockCoord & 15;
        return local >= 1 && local <= 14;
    }

    static int toInterior(int blockCoord) {
        int local = blockCoord & 15;
        if (local == 0) {
            return blockCoord + 1;
        }
        if (local == 15) {
            return blockCoord - 1;
        }
        return blockCoord;
    }
}

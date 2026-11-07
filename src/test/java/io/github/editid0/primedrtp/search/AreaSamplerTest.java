package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.config.WorldSettings.Shape;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaSamplerTest {

    private static final int N = 200_000;

    @Test
    void squareRingBoundsAndInterior() {
        SplittableRandom rnd = new SplittableRandom(1);
        int cx = 100;
        int cz = -300;
        for (int i = 0; i < N; i++) {
            long p = AreaSampler.sample(rnd, Shape.SQUARE, cx, cz, 500, 2000);
            int x = SearchOptions.unpackX(p);
            int z = SearchOptions.unpackZ(p);
            int cheb = Math.max(Math.abs(x - cx), Math.abs(z - cz));
            assertTrue(cheb >= 500 && cheb <= 2000, "out of ring: " + cheb);
            assertTrue(AreaSampler.isInterior(x) && AreaSampler.isInterior(z), "not interior: " + x + "," + z);
        }
    }

    @Test
    void circleRingBoundsAndInterior() {
        SplittableRandom rnd = new SplittableRandom(2);
        for (int i = 0; i < N; i++) {
            long p = AreaSampler.sample(rnd, Shape.CIRCLE, 0, 0, 300, 1000);
            int x = SearchOptions.unpackX(p);
            int z = SearchOptions.unpackZ(p);
            double d = Math.sqrt((double) x * x + (double) z * z);
            assertTrue(d >= 300 && d <= 1000, "out of annulus: " + d);
            assertTrue(AreaSampler.isInterior(x) && AreaSampler.isInterior(z));
        }
    }

    @Test
    void squareUniformByArea() {
        // Ring [0, R]: fraction with Chebyshev distance < R/2 should be 1/4; quadrants equal.
        SplittableRandom rnd = new SplittableRandom(3);
        int r = 4000;
        int inner = 0;
        int[] quadrant = new int[4];
        for (int i = 0; i < N; i++) {
            long p = AreaSampler.sample(rnd, Shape.SQUARE, 0, 0, 0, r);
            int x = SearchOptions.unpackX(p);
            int z = SearchOptions.unpackZ(p);
            if (Math.max(Math.abs(x), Math.abs(z)) < r / 2) {
                inner++;
            }
            quadrant[(x >= 0 ? 0 : 1) + (z >= 0 ? 0 : 2)]++;
        }
        assertEquals(0.25, inner / (double) N, 0.01);
        for (int q : quadrant) {
            assertEquals(0.25, q / (double) N, 0.01);
        }
    }

    @Test
    void squareRingUniformAcrossAnnulus() {
        // Ring [1000, 2000]: area of [1000,1500) band = 1500²-1000² = 1.25M of total 3M -> 0.4167.
        SplittableRandom rnd = new SplittableRandom(4);
        int band = 0;
        for (int i = 0; i < N; i++) {
            long p = AreaSampler.sample(rnd, Shape.SQUARE, 0, 0, 1000, 2000);
            int cheb = Math.max(Math.abs(SearchOptions.unpackX(p)), Math.abs(SearchOptions.unpackZ(p)));
            if (cheb < 1500) {
                band++;
            }
        }
        assertEquals(1.25 / 3.0, band / (double) N, 0.01);
    }

    @Test
    void circleUniformByArea() {
        // Annulus [500, 1500]: fraction with r < 1000 = (1000²-500²)/(1500²-500²) = 0.375.
        SplittableRandom rnd = new SplittableRandom(5);
        int inner = 0;
        int[] octant = new int[8];
        for (int i = 0; i < N; i++) {
            long p = AreaSampler.sample(rnd, Shape.CIRCLE, 0, 0, 500, 1500);
            int x = SearchOptions.unpackX(p);
            int z = SearchOptions.unpackZ(p);
            if ((long) x * x + (long) z * z < 1_000_000L) {
                inner++;
            }
            double a = Math.atan2(z, x) + Math.PI;
            octant[Math.min(7, (int) (a / (Math.PI / 4)))]++;
        }
        assertEquals(0.375, inner / (double) N, 0.01);
        for (int o : octant) {
            assertEquals(0.125, o / (double) N, 0.01);
        }
    }

    @Test
    void degenerateAreasDoNotLoopForever() {
        SplittableRandom rnd = new SplittableRandom(6);
        // Centre on a chunk edge with radius 0: falls back to the nearest interior column.
        long p = AreaSampler.sample(rnd, Shape.SQUARE, 16, 31, 0, 0);
        assertEquals(17, SearchOptions.unpackX(p));
        assertEquals(30, SearchOptions.unpackZ(p));
        long c = AreaSampler.sample(rnd, Shape.CIRCLE, 5, 5, 0, 0);
        assertEquals(5, SearchOptions.unpackX(c));
        assertEquals(5, SearchOptions.unpackZ(c));
        // Thin ring min == max still in bounds (Chebyshev exact).
        for (int i = 0; i < 1000; i++) {
            long q = AreaSampler.sample(rnd, Shape.SQUARE, 0, 0, 100, 100);
            int cheb = Math.max(Math.abs(SearchOptions.unpackX(q)), Math.abs(SearchOptions.unpackZ(q)));
            assertEquals(100, cheb);
        }
    }

    @Test
    void negativeCoordinatesInterior() {
        assertTrue(AreaSampler.isInterior(-2));   // -2 & 15 = 14
        assertTrue(!AreaSampler.isInterior(-1));  // 15
        assertTrue(!AreaSampler.isInterior(-16)); // 0
        assertEquals(-2, AreaSampler.toInterior(-1));
        assertEquals(-15, AreaSampler.toInterior(-16));
    }

    @Test
    void invalidRingThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AreaSampler.sample(new SplittableRandom(), Shape.SQUARE, 0, 0, 10, 5));
    }
}

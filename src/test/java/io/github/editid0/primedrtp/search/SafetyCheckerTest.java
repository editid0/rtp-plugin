package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.search.SafetyChecker.Mode;
import io.github.editid0.primedrtp.search.SafetyChecker.Reason;
import io.github.editid0.primedrtp.search.SafetyChecker.Result;
import io.github.editid0.primedrtp.search.SafetyChecker.Rules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyCheckerTest {

    private static final int MIN = -64;
    private static final int MAX = 319;

    private static Rules surface(boolean sky, boolean avoidLiquids) {
        return new Rules(Mode.SURFACE, MIN + 1, MAX - 1, sky, avoidLiquids);
    }

    /** Flat stone ground up to y=64 around (5,5). */
    private static FakeBlockView flat() {
        return new FakeBlockView(MIN, MAX).layer(5, 5, 2, MIN, 64, '#');
    }

    @Test
    void surfaceFlatGroundIsSafe() {
        Result r = SafetyChecker.find(flat(), 5, 5, surface(true, true), 0);
        assertTrue(r.safe());
        assertEquals(65, r.feetY());
    }

    @Test
    void surfaceUnsafeFloor() {
        FakeBlockView v = flat().set(5, 64, 5, 'm');
        assertEquals(Reason.UNSAFE_FLOOR, SafetyChecker.find(v, 5, 5, surface(false, true), 0).reason());
    }

    @Test
    void surfaceWaterIsLiquid() {
        FakeBlockView v = flat().fill(5, 5, 62, 64, 'w');
        assertEquals(Reason.LIQUID, SafetyChecker.find(v, 5, 5, surface(false, true), 0).reason());
        // Without avoid-liquids, water still isn't a floor.
        assertEquals(Reason.LIQUID, SafetyChecker.find(v, 5, 5, surface(false, false), 0).reason());
    }

    @Test
    void waterloggedFloorRejectedOnlyWithAvoidLiquids() {
        FakeBlockView v = flat().set(5, 64, 5, 's');
        assertEquals(Reason.LIQUID, SafetyChecker.find(v, 5, 5, surface(false, true), 0).reason());
        assertTrue(SafetyChecker.find(v, 5, 5, surface(false, false), 0).safe());
    }

    @Test
    void lavaAtFeetAlwaysRejected() {
        // Lava is motion-blocking, so place it as a "plant-level" pocket: floor stays stone via verify().
        FakeBlockView v = flat().set(5, 65, 5, 'l');
        assertEquals(Reason.LIQUID, SafetyChecker.verify(v, 5, 65, 5, surface(false, false)));
    }

    @Test
    void leavesAboveFailSkyAccessButNotOtherwise() {
        FakeBlockView v = flat().layer(5, 5, 1, 70, 71, 'L');
        assertEquals(Reason.NO_SKY, SafetyChecker.find(v, 5, 5, surface(true, true), 0).reason());
        Result r = SafetyChecker.find(v, 5, 5, surface(false, true), 0);
        assertTrue(r.safe());
        assertEquals(65, r.feetY());
    }

    @Test
    void grassAtFeetIsFine() {
        FakeBlockView v = flat().set(5, 65, 5, 'g');
        assertTrue(SafetyChecker.find(v, 5, 5, surface(true, true), 0).safe());
    }

    @Test
    void unsafeNearbyInCube() {
        assertEquals(Reason.UNSAFE_NEARBY,
                SafetyChecker.find(flat().set(6, 66, 6, 'f'), 5, 5, surface(true, true), 0).reason());
        assertEquals(Reason.UNSAFE_NEARBY,
                SafetyChecker.find(flat().set(4, 64, 5, 'c'), 5, 5, surface(true, true), 0).reason());
        // Two blocks away is fine.
        assertTrue(SafetyChecker.find(flat().set(7, 65, 5, 'f'), 5, 5, surface(true, true), 0).safe());
        // Above the head (feet+2) is outside the cube.
        assertTrue(SafetyChecker.find(flat().set(6, 67, 5, 'f'), 5, 5, surface(false, true), 0).safe());
    }

    @Test
    void yRangeEnforced() {
        Rules rules = new Rules(Mode.SURFACE, MIN + 1, 60, false, true);
        assertEquals(Reason.Y_RANGE, SafetyChecker.find(flat(), 5, 5, rules, 0).reason());
        Rules low = new Rules(Mode.SURFACE, 70, MAX - 1, false, true);
        assertEquals(Reason.Y_RANGE, SafetyChecker.find(flat(), 5, 5, low, 0).reason());
    }

    @Test
    void emptyColumnIsVoid() {
        FakeBlockView v = new FakeBlockView(0, 255);
        assertEquals(Reason.VOID, SafetyChecker.find(v, 5, 5, new Rules(Mode.END, 1, 254, false, true), 0).reason());
        assertEquals(Reason.VOID, SafetyChecker.find(v, 5, 5, new Rules(Mode.SURFACE, 1, 254, false, true), 0).reason());
    }

    @Test
    void endRejectsFloorNearBottom() {
        FakeBlockView v = new FakeBlockView(0, 255).layer(5, 5, 1, 0, 1, '#');
        assertEquals(Reason.VOID, SafetyChecker.find(v, 5, 5, new Rules(Mode.END, 1, 254, false, true), 0).reason());
        FakeBlockView island = new FakeBlockView(0, 255).layer(5, 5, 1, 50, 60, '#');
        Result r = SafetyChecker.find(island, 5, 5, new Rules(Mode.END, 1, 254, true, true), 0);
        assertTrue(r.safe());
        assertEquals(61, r.feetY());
    }

    @Test
    void headAtWorldTopRejected() {
        FakeBlockView v = new FakeBlockView(0, 100).layer(5, 5, 1, 0, 99, '#');
        assertEquals(Reason.Y_RANGE, SafetyChecker.find(v, 5, 5, new Rules(Mode.SURFACE, 1, 200, false, true), 0).reason());
    }

    // ---- CAVE ---------------------------------------------------------------------------------------------------

    /** Nether-like column: bedrock 0 & 123..127, netherrack between, air pockets cut out. */
    private static FakeBlockView nether() {
        FakeBlockView v = new FakeBlockView(0, 255);
        v.layer(5, 5, 1, 0, 0, 'b').layer(5, 5, 1, 1, 122, '#').layer(5, 5, 1, 123, 127, 'b');
        return v;
    }

    private static final Rules CAVE = new Rules(Mode.CAVE, 1, 122, false, true);

    @Test
    void caveFindsPocketAndNeverRoof() {
        FakeBlockView v = nether().layer(5, 5, 1, 40, 42, '.');
        Result r = SafetyChecker.find(v, 5, 5, CAVE, 122);
        assertTrue(r.safe());
        assertEquals(40, r.feetY());
        // Above the roof is open air (y 128+) but maxFeetY prevents it.
        assertTrue(r.feetY() <= 122);
    }

    @Test
    void caveSolidColumnHasNoPocket() {
        assertEquals(Reason.NO_POCKET, SafetyChecker.find(nether(), 5, 5, CAVE, 60).reason());
    }

    @Test
    void caveOneHighPocketIsNotEnough() {
        FakeBlockView v = nether().layer(5, 5, 1, 40, 40, '.');
        assertEquals(Reason.NO_POCKET, SafetyChecker.find(v, 5, 5, CAVE, 100).reason());
    }

    @Test
    void caveScanStartsAtStartAndWraps() {
        FakeBlockView v = nether().layer(5, 5, 1, 30, 31, '.').layer(5, 5, 1, 90, 91, '.');
        assertEquals(90, SafetyChecker.find(v, 5, 5, CAVE, 122).feetY());
        assertEquals(30, SafetyChecker.find(v, 5, 5, CAVE, 60).feetY());
        // Start below the lower pocket: wraps to top and finds 90.
        assertEquals(90, SafetyChecker.find(v, 5, 5, CAVE, 20).feetY());
    }

    @Test
    void caveSkipsLavaPocketAndReportsReason() {
        FakeBlockView v = nether().layer(5, 5, 1, 40, 41, '.').set(5, 39, 5, '#').set(5, 40, 5, 'l');
        Result r = SafetyChecker.find(v, 5, 5, CAVE, 122);
        // Lava has no collision, so the pocket passes the shape test and verify() reports the lava.
        assertEquals(Reason.LIQUID, r.reason());
    }

    @Test
    void caveUnsafeNeighbourReportsDetailedReason() {
        FakeBlockView v = nether().layer(5, 5, 1, 40, 41, '.').set(6, 41, 5, 'f');
        assertEquals(Reason.UNSAFE_NEARBY, SafetyChecker.find(v, 5, 5, CAVE, 122).reason());
    }

    @Test
    void verifyFixedPosition() {
        FakeBlockView v = flat();
        assertNull(SafetyChecker.verify(v, 5, 65, 5, surface(true, true)));
        assertEquals(Reason.FLOOR_NOT_SOLID, SafetyChecker.verify(v, 5, 66, 5, surface(false, true)));
        assertEquals(Reason.OBSTRUCTED, SafetyChecker.verify(v, 5, 64, 5, surface(false, true)));
        v.set(5, 66, 5, '#');
        assertEquals(Reason.OBSTRUCTED, SafetyChecker.verify(v, 5, 65, 5, surface(false, true)));
    }
}

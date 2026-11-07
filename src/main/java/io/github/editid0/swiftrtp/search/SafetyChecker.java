package io.github.editid0.swiftrtp.search;

import org.jspecify.annotations.Nullable;

/**
 * Pure block-rule logic deciding where (if anywhere) a player can stand in a column. Works on a {@link BlockView}
 * so it is unit-testable without a server; callers are responsible for running it on the thread owning the chunk.
 * <p>
 * Rules for a feet position {@code (x, feetY, z)} (floor = feetY−1, head = feetY+1):
 * <ol>
 *   <li>feetY within {@link Rules#minFeetY()}..{@link Rules#maxFeetY()} and the head inside the world;</li>
 *   <li>floor exists (not the void; END additionally needs {@value #END_VOID_MARGIN} blocks above world min);</li>
 *   <li>floor is solid and not in {@code unsafe-floor};</li>
 *   <li>feet and head are passable and not lava; with {@code avoid-liquids} floor/feet/head contain no liquid;</li>
 *   <li>SURFACE/END with {@code require-sky-access}: nothing motion-blocking (incl. leaves) above the floor;</li>
 *   <li>nothing in {@code unsafe-nearby} in the 3×3×3 cube centred on the feet (covers floor level, feet, head).</li>
 * </ol>
 */
final class SafetyChecker {

    /** END: floors this close to the world bottom count as void. */
    static final int END_VOID_MARGIN = 4;

    private SafetyChecker() {}

    /** Resolved search mode (AUTO already mapped by the caller). */
    enum Mode { SURFACE, CAVE, END }

    /** Rejection reasons; {@link #key} is used in the debug histogram. */
    enum Reason {
        Y_RANGE("y-range"),
        VOID("void"),
        FLOOR_NOT_SOLID("floor-not-solid"),
        UNSAFE_FLOOR("unsafe-floor"),
        OBSTRUCTED("obstructed"),
        LIQUID("liquid"),
        NO_SKY("no-sky"),
        UNSAFE_NEARBY("unsafe-nearby"),
        NO_POCKET("no-cave-pocket");

        final String key;

        Reason(String key) {
            this.key = key;
        }
    }

    /**
     * @param minFeetY lowest allowed feet Y (inclusive)
     * @param maxFeetY highest allowed feet Y (inclusive)
     */
    record Rules(Mode mode, int minFeetY, int maxFeetY, boolean requireSkyAccess, boolean avoidLiquids) {}

    /** Either a safe {@code feetY} ({@code reason == null}) or a rejection reason. */
    record Result(int feetY, @Nullable Reason reason) {
        boolean safe() {
            return reason == null;
        }

        static Result ok(int feetY) {
            return new Result(feetY, null);
        }

        static Result fail(Reason reason) {
            return new Result(Integer.MIN_VALUE, reason);
        }
    }

    /**
     * Finds a safe standing position in column (x, z).
     *
     * @param caveStartY CAVE only: feet Y to start the downward scan at (wraps around to the top once); values
     *                   outside the allowed range are clamped. Lets callers randomise heights deterministically.
     */
    static Result find(BlockView v, int x, int z, Rules rules, int caveStartY) {
        if (rules.mode() == Mode.CAVE) {
            return findCave(v, x, z, rules, caveStartY);
        }
        int floor = v.surfaceY(x, z);
        if (floor < v.minY()) {
            return Result.fail(Reason.VOID);
        }
        Reason r = verify(v, x, floor + 1, z, rules);
        return r == null ? Result.ok(floor + 1) : Result.fail(r);
    }

    /** Effective feet range for CAVE scans: config range intersected with the world (floor and head inside). */
    static int caveTop(BlockView v, Rules rules) {
        return Math.min(rules.maxFeetY(), v.maxY() - 1);
    }

    static int caveBottom(BlockView v, Rules rules) {
        return Math.max(rules.minFeetY(), v.minY() + 1);
    }

    private static Result findCave(BlockView v, int x, int z, Rules rules, int startY) {
        int top = caveTop(v, rules);
        int bottom = caveBottom(v, rules);
        if (top < bottom) {
            return Result.fail(Reason.Y_RANGE);
        }
        int start = Math.clamp(startY, bottom, top);
        Reason last = null;
        // Pass 1: start..bottom, pass 2: top..start+1.
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? start : top;
            int to = pass == 0 ? bottom : start + 1;
            for (int y = from; y >= to; y--) {
                // Cheap shape test first: solid floor with two passable blocks above.
                if (!v.passable(x, y + 1, z) || !v.passable(x, y, z) || !v.solid(x, y - 1, z)) {
                    continue;
                }
                Reason r = verify(v, x, y, z, rules);
                if (r == null) {
                    return Result.ok(y);
                }
                last = r;
            }
        }
        return Result.fail(last != null ? last : Reason.NO_POCKET);
    }

    /**
     * Checks a fixed feet position against every block rule. Returns null if safe, otherwise the first failed rule.
     */
    static @Nullable Reason verify(BlockView v, int x, int feetY, int z, Rules rules) {
        int floor = feetY - 1;
        int head = feetY + 1;
        if (feetY < rules.minFeetY() || feetY > rules.maxFeetY() || head > v.maxY()) {
            return Reason.Y_RANGE;
        }
        if (floor < v.minY() || (rules.mode() == Mode.END && floor < v.minY() + END_VOID_MARGIN)) {
            return Reason.VOID;
        }
        if (!v.solid(x, floor, z)) {
            return v.liquid(x, floor, z) ? Reason.LIQUID : Reason.FLOOR_NOT_SOLID;
        }
        if (v.unsafeFloor(x, floor, z)) {
            return Reason.UNSAFE_FLOOR;
        }
        if (v.lava(x, feetY, z) || v.lava(x, head, z)) {
            return Reason.LIQUID;
        }
        if (!v.passable(x, feetY, z) || !v.passable(x, head, z)) {
            return Reason.OBSTRUCTED;
        }
        if (rules.avoidLiquids()
                && (v.liquid(x, floor, z) || v.liquid(x, feetY, z) || v.liquid(x, head, z))) {
            return Reason.LIQUID;
        }
        if (rules.requireSkyAccess() && rules.mode() != Mode.CAVE && v.skyY(x, z) > floor) {
            return Reason.NO_SKY;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (v.unsafeNearby(x + dx, feetY + dy, z + dz)) {
                        return Reason.UNSAFE_NEARBY;
                    }
                }
            }
        }
        return null;
    }
}

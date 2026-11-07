package io.github.editid0.swiftrtp.search;

/**
 * Minimal read-only block access used by {@link SafetyChecker}, so the safety rules can be unit-tested against a
 * fake grid without a server. The Bukkit implementation is {@link BukkitBlockView}.
 * <p>
 * Coordinates are absolute block coordinates. Implementations must tolerate any {@code y} (outside the world:
 * not solid, passable, not liquid, not unsafe) and must not load chunks: blocks in inaccessible chunks behave like
 * "unknown but harmless" (not solid, passable, not liquid, not unsafe).
 */
interface BlockView {

    /** Lowest block Y of the world (inclusive). */
    int minY();

    /** Highest block Y of the world (inclusive). */
    int maxY();

    /** Y of the highest motion-blocking block ignoring leaves (fluids count), or {@code < minY()} if none. */
    int surfaceY(int x, int z);

    /** Y of the highest motion-blocking block including leaves (fluids count), or {@code < minY()} if none. */
    int skyY(int x, int z);

    /** Has a full-ish collision box that can be stood on (not a fluid). */
    boolean solid(int x, int y, int z);

    /** No collision; a player body can occupy it. */
    boolean passable(int x, int y, int z);

    /** Water, lava, bubble columns, water plants, or a waterlogged block. */
    boolean liquid(int x, int y, int z);

    /** Lava specifically (never acceptable for feet/head, regardless of {@code avoid-liquids}). */
    boolean lava(int x, int y, int z);

    /** Block is in {@code search.unsafe-floor}. */
    boolean unsafeFloor(int x, int y, int z);

    /** Block is in {@code search.unsafe-nearby}. */
    boolean unsafeNearby(int x, int y, int z);
}

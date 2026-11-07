package io.github.editid0.swiftrtp.api;

import org.bukkit.World;

/**
 * Extra veto on candidate locations. Register via {@link SwiftRTPApi#registerFilter(LocationFilter)}.
 * <p>
 * Called on the thread owning the candidate's chunk, with the chunk loaded, after built-in safety checks passed.
 * Must be fast and must not load other chunks. {@code (x, y, z)} is the block the player's feet will occupy.
 */
public interface LocationFilter {

    /** Short id shown in debug output, e.g. "worldguard". */
    String name();

    boolean isAllowed(World world, int x, int y, int z);

    /**
     * Claim/region-protection filters only apply to worlds with {@code search.avoid-claims: true};
     * non-claim filters always apply.
     */
    default boolean isClaimFilter() {
        return false;
    }
}

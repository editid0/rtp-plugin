package io.github.editid0.swiftrtp.config;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * Server-backed lookups the settings parser needs. Abstracted so the parser stays a pure, unit-testable function
 * ({@link BukkitConfigLookups} is the live implementation; tests use a fake).
 * <p>
 * All keys passed in are already normalised: lower-case, namespaced ({@code minecraft:lava}).
 */
public interface ConfigLookups {

    /** A block material by key, or null if unknown / not a block. */
    @Nullable Material block(String key);

    /** An item material by key, or null if unknown / not an item (or air). */
    @Nullable Material item(String key);

    /** The block materials of a block tag (key without '#'), or null if the tag does not exist. */
    @Nullable Collection<Material> blockTag(String key);

    /** Registry kinds that are validated lightly by key. */
    enum Kind { SOUND, PARTICLE, EFFECT, BIOME }

    /** Whether the key exists in that registry. Implementations unable to check return true. */
    boolean exists(Kind kind, String key);
}

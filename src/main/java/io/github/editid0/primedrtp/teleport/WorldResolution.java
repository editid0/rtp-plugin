package io.github.editid0.primedrtp.teleport;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure target-world resolution for {@code /rtp} without an explicit world:
 * {@code world-redirects[current]} (if that world is loaded) → current world (if RTP-enabled) →
 * {@code default-world} (if loaded) → none.
 */
public final class WorldResolution {

    private WorldResolution() {}

    /**
     * @param current     name of the player's current world
     * @param redirects   world-redirects from config
     * @param enabled     whether a world name is RTP-enabled
     * @param defaultWorld default-world from config (nullable)
     * @param loaded      whether a world name is currently loaded
     */
    public static Optional<String> resolve(String current, Map<String, String> redirects, Predicate<String> enabled,
                                           @Nullable String defaultWorld, Predicate<String> loaded) {
        String redirect = redirects.get(current);
        if (redirect != null && !redirect.isBlank() && loaded.test(redirect)) {
            return Optional.of(redirect);
        }
        if (enabled.test(current)) {
            return Optional.of(current);
        }
        if (defaultWorld != null && !defaultWorld.isBlank() && loaded.test(defaultWorld)) {
            return Optional.of(defaultWorld);
        }
        return Optional.empty();
    }
}

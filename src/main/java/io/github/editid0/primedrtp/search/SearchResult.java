package io.github.editid0.primedrtp.search;

import org.bukkit.Location;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of a search. On success {@code location} is the centre of the block the player's feet occupy
 * (x+0.5, y, z+0.5) with a random yaw and pitch 0.
 */
public record SearchResult(@Nullable Location location, int attempts, long elapsedNanos, @Nullable Failure failure) {

    public enum Failure {
        /** max-attempts exhausted without a safe spot. */
        NO_SAFE_LOCATION,
        /** World unloaded / settings disabled. */
        WORLD_UNAVAILABLE,
        /** Configured area is empty (e.g. minRadius &gt; maxRadius after border clamp). */
        INVALID_AREA,
        /** Search cancelled (plugin stopping). */
        CANCELLED,
        /** Unexpected exception (logged). */
        ERROR
    }

    public boolean success() {
        return location != null;
    }

    public static SearchResult found(Location location, int attempts, long elapsedNanos) {
        return new SearchResult(location, attempts, elapsedNanos, null);
    }

    public static SearchResult failed(Failure failure, int attempts, long elapsedNanos) {
        return new SearchResult(null, attempts, elapsedNanos, failure);
    }
}

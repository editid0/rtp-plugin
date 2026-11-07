package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.Service;
import io.github.editid0.primedrtp.config.WorldSettings;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Finds a random safe location in a world according to {@link WorldSettings}. Callable from any thread; the
 * future may complete on any thread (callers must hop to the right scheduler before touching world/entity state).
 * Never completes exceptionally — failures are reported via {@link SearchResult#failure()}.
 */
public interface LocationSearcher extends Service {

    CompletableFuture<SearchResult> search(World world, WorldSettings settings, SearchOptions options);

    /**
     * Synchronously re-checks that a previously found location is still safe (blocks may have changed). Must be
     * called on the thread owning the location's chunk with that chunk loaded. Applies the same rules (incl.
     * filters) as {@link #search}, except area/biome/chunk-preference/player-distance checks.
     */
    boolean isStillSafe(Location location, WorldSettings settings);
}

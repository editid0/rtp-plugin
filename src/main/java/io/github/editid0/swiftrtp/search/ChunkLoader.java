package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.Service;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Single choke point for every async chunk load the plugin performs. BACKGROUND loads are throttled by
 * {@code performance.*} (concurrency, loads/second, MSPT and empty-server pause); URGENT loads (a player is waiting)
 * bypass throttling and use Paper's urgent priority.
 * <p>
 * Thread contract: callable from any thread. The returned future completes on the thread owning the chunk (main
 * thread on Paper, region thread on Folia), so non-async dependents attached immediately may access that chunk.
 * Completes exceptionally with {@link java.util.concurrent.CancellationException} if the loader is stopped, and with
 * {@code null} chunk never — a missing chunk (generate=false and not generated) completes exceptionally with
 * {@link ChunkNotGeneratedException}.
 */
public interface ChunkLoader extends Service {

    enum Priority { URGENT, BACKGROUND }

    CompletableFuture<Chunk> load(World world, int chunkX, int chunkZ, boolean generate, Priority priority);

    /** True while BACKGROUND work is paused (MSPT too high / server empty / startup delay). */
    boolean isPaused();

    /** Human-readable reason for the pause, or null when not paused. */
    String pauseReason();

    int inFlight();

    /** Queued BACKGROUND requests not yet dispatched. */
    int queued();

    long totalLoaded();

    final class ChunkNotGeneratedException extends RuntimeException {
        public ChunkNotGeneratedException(int x, int z) {
            super("chunk " + x + "," + z + " not generated", null, false, false);
        }
    }
}

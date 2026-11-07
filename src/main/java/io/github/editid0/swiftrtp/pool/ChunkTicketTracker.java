package io.github.editid0.swiftrtp.pool;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.search.ChunkLoader;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.UUID;

/**
 * Reference-counted plugin chunk tickets. Counting happens in {@link TicketRefCounter} (any thread); on a 0&rarr;1 or
 * 1&rarr;0 transition the real Bukkit ticket is reconciled on the region thread owning the chunk.
 * <p>
 * Reconciliation is idempotent and order independent: the region task reads the <em>current</em> count and adds the
 * ticket if it is &gt; 0, removes it otherwise. So even if an add and a remove task for the same chunk run in an
 * unexpected order, the last one to run sees the final count and leaves the correct state. A ticket is only added
 * while the chunk is loaded (otherwise it is loaded asynchronously first), so adding never triggers a synchronous
 * chunk load.
 */
final class ChunkTicketTracker implements TicketRefs {

    private final SwiftRTP plugin;
    private final TicketRefCounter counter = new TicketRefCounter();
    private volatile boolean closed;

    ChunkTicketTracker(SwiftRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void acquire(UUID world, int chunkX, int chunkZ) {
        if (closed) {
            return;
        }
        if (counter.increment(world, ChunkArea.key(chunkX, chunkZ))) {
            reconcile(world, chunkX, chunkZ);
        }
    }

    @Override
    public void release(UUID world, int chunkX, int chunkZ) {
        if (closed) {
            return;
        }
        if (counter.decrement(world, ChunkArea.key(chunkX, chunkZ))) {
            reconcile(world, chunkX, chunkZ);
        }
    }

    int chunkCount(UUID world) {
        return counter.chunkCount(world);
    }

    int totalChunks() {
        return counter.totalChunks();
    }

    /** Forgets all references in a world that is unloading (its tickets disappear with it). */
    int forgetWorld(UUID world) {
        return counter.clearWorld(world);
    }

    /** Stops all further ticket changes and forgets every reference (plugin disabling). */
    void close() {
        closed = true;
        counter.clearAll();
    }

    private void reconcile(UUID worldId, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldId);
        if (world == null || closed) {
            return;
        }
        try {
            plugin.scheduling().regionOrNow(world, chunkX, chunkZ, () -> apply(world, worldId, chunkX, chunkZ));
        } catch (RuntimeException e) {
            // Plugin disabling / world unloading: nothing to do, Paper drops our tickets with it.
            plugin.debug("ticket reconcile for " + world.getName() + " " + chunkX + "," + chunkZ + " skipped: " + e);
        }
    }

    /** Runs on the thread owning the chunk. */
    private void apply(World world, UUID worldId, int chunkX, int chunkZ) {
        if (closed) {
            return;
        }
        boolean wanted = counter.count(worldId, ChunkArea.key(chunkX, chunkZ)) > 0;
        if (!wanted) {
            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
            return;
        }
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            return;
        }
        // Not loaded (unloaded between the load and this task): load async first, then re-apply on its thread.
        plugin.chunkLoader().load(world, chunkX, chunkZ, false, ChunkLoader.Priority.BACKGROUND)
                .whenComplete((chunk, error) -> {
                    if (error == null && !closed) {
                        reconcile(worldId, chunkX, chunkZ);
                    }
                });
    }
}

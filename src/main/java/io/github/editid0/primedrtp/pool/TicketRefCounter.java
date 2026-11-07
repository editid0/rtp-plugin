package io.github.editid0.primedrtp.pool;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure, thread-safe reference counter for chunk tickets, keyed by (world, chunk key).
 * <p>
 * Bukkit plugin chunk tickets are not reference counted (a plugin holds at most one ticket per chunk), so pooled
 * locations whose keep-loaded areas overlap must share them: the real ticket is added when a chunk's count goes
 * 0&rarr;1 and removed when it goes 1&rarr;0. This class only does the counting; {@link ChunkTicketTracker} applies
 * the transitions on the owning region thread.
 */
final class TicketRefCounter {

    private final Map<UUID, Map<Long, Integer>> counts = new HashMap<>();
    private int total;

    /** @return true when this was the first reference (a real ticket must be added) */
    synchronized boolean increment(UUID world, long chunkKey) {
        Map<Long, Integer> perWorld = counts.computeIfAbsent(world, w -> new HashMap<>());
        int next = perWorld.merge(chunkKey, 1, Integer::sum);
        if (next == 1) {
            total++;
            return true;
        }
        return false;
    }

    /**
     * Decrements a reference. Unknown keys are ignored (returns false), so a double release can never drive the
     * count negative or remove a ticket another holder still needs.
     *
     * @return true when this was the last reference (the real ticket must be removed)
     */
    synchronized boolean decrement(UUID world, long chunkKey) {
        Map<Long, Integer> perWorld = counts.get(world);
        if (perWorld == null) {
            return false;
        }
        Integer current = perWorld.get(chunkKey);
        if (current == null) {
            return false;
        }
        if (current <= 1) {
            perWorld.remove(chunkKey);
            total--;
            if (perWorld.isEmpty()) {
                counts.remove(world);
            }
            return true;
        }
        perWorld.put(chunkKey, current - 1);
        return false;
    }

    synchronized int count(UUID world, long chunkKey) {
        Map<Long, Integer> perWorld = counts.get(world);
        if (perWorld == null) {
            return 0;
        }
        return perWorld.getOrDefault(chunkKey, 0);
    }

    /** Number of distinct chunks with at least one reference in the world. */
    synchronized int chunkCount(UUID world) {
        Map<Long, Integer> perWorld = counts.get(world);
        return perWorld == null ? 0 : perWorld.size();
    }

    synchronized int totalChunks() {
        return total;
    }

    /** Forgets every reference in the world (world unloaded). @return chunks forgotten */
    synchronized int clearWorld(UUID world) {
        Map<Long, Integer> removed = counts.remove(world);
        if (removed == null) {
            return 0;
        }
        total -= removed.size();
        return removed.size();
    }

    synchronized void clearAll() {
        counts.clear();
        total = 0;
    }
}

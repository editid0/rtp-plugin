package io.github.editid0.primedrtp.pool;

import java.util.UUID;

/** {@link TicketRefs} backed by a real {@link TicketRefCounter}, for asserting balanced acquire/release. */
final class RecordingRefs implements TicketRefs {
    final TicketRefCounter counter = new TicketRefCounter();
    int realAdds;
    int realRemoves;

    @Override
    public void acquire(UUID world, int chunkX, int chunkZ) {
        if (counter.increment(world, ChunkArea.key(chunkX, chunkZ))) {
            realAdds++;
        }
    }

    @Override
    public void release(UUID world, int chunkX, int chunkZ) {
        if (counter.decrement(world, ChunkArea.key(chunkX, chunkZ))) {
            realRemoves++;
        }
    }
}

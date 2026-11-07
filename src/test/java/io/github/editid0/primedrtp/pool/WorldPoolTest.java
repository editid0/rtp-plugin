package io.github.editid0.primedrtp.pool;

import io.github.editid0.primedrtp.config.WorldSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldPoolTest {

    private final UUID worldId = UUID.randomUUID();
    private final RecordingRefs refs = new RecordingRefs();
    private long ids;

    private WorldPool pool(WorldSettings ws) {
        return new WorldPool(worldId, "world", ws);
    }

    private PooledLocation loc(int x, int z, long createdAt) {
        return new PooledLocation(++ids, worldId, "world", x + 0.5, 70, z + 0.5, 0f, createdAt);
    }

    /** Runs a full job: start, acquire its keep-loaded chunks, commit. */
    private WorldPool.Commit fillOne(WorldPool p, int x, int z, long createdAt) {
        WorldPool.Start s = p.nextStart(10, createdAt, refs);
        assertNotNull(s);
        assertTrue(p.tryReserve(s.job(), x, z, p.settings().pool().minSpacing()));
        for (long k : ChunkArea.square(x >> 4, z >> 4, 1)) {
            s.job().acquire(ChunkArea.keyX(k), ChunkArea.keyZ(k));
        }
        WorldPool.Commit c = p.commit(s.job(), loc(x, z, createdAt));
        if (c.status() != WorldPool.CommitStatus.ADDED) {
            s.job().cancel();
        }
        p.endJob(s.job());
        return c;
    }

    private void release(List<PoolEntry> entries) {
        for (PoolEntry e : entries) refs.releaseAll(worldId, e.ticketChunks());
    }

    @Test
    void jobLimitsRespectTargetAndMaxSearches() {
        WorldPool p = pool(TestSettings.world(3));
        assertNotNull(p.nextStart(2, 0, refs));
        assertNotNull(p.nextStart(2, 0, refs));
        assertNull(p.nextStart(2, 0, refs), "max searches per world");
        assertEquals(2, p.searching());

        WorldPool q = pool(TestSettings.world(1));
        assertNotNull(q.nextStart(5, 0, refs));
        assertNull(q.nextStart(5, 0, refs), "ready + searching may not exceed target");
    }

    @Test
    void fifoServingAndRestoreToFront() {
        WorldPool p = pool(TestSettings.world(3));
        assertEquals(WorldPool.CommitStatus.ADDED, fillOne(p, 0, 0, 1).status());
        assertEquals(WorldPool.CommitStatus.ADDED, fillOne(p, 1000, 0, 2).status());
        List<PoolEntry> expired = new ArrayList<>();
        PoolEntry first = p.poll(10, expired);
        assertEquals(1, first.location().createdAt(), "oldest first");
        assertTrue(p.offerFront(first, 10));
        assertSame(first, p.poll(10, expired));
        assertEquals(1, p.stats(0, false, null).totalServed());
        assertTrue(expired.isEmpty());
    }

    @Test
    void fullPoolRejectsCommitAndRestore() {
        WorldPool p = pool(TestSettings.world(1));
        fillOne(p, 0, 0, 1);
        // Pool is full, so a job cannot even start.
        assertNull(p.nextStart(5, 0, refs));
        PoolEntry e = p.poll(0, new ArrayList<>());
        WorldPool.Start s = p.nextStart(5, 0, refs);
        assertNotNull(s);
        assertEquals(WorldPool.CommitStatus.ADDED, p.commit(s.job(), loc(500, 500, 0)).status());
        p.endJob(s.job());
        assertFalse(p.offerFront(e, 0), "full: caller must release");
        release(List.of(e));
    }

    @Test
    void clearInvalidatesRunningJobsAndEntries() {
        WorldPool p = pool(TestSettings.world(3));
        fillOne(p, 0, 0, 1);
        WorldPool.Start running = p.nextStart(5, 0, refs);
        running.job().acquire(5, 5);
        PoolEntry taken = p.poll(0, new ArrayList<>());
        fillOne(p, 1000, 1000, 1);

        WorldPool.Released r = p.clear();
        assertTrue(r.cleared());
        assertEquals(1, r.released().size());
        assertEquals(1, r.cancelledJobs().size());
        release(r.released());
        r.cancelledJobs().forEach(PoolJob::cancel);

        assertEquals(WorldPool.CommitStatus.STALE, p.commit(running.job(), loc(9, 9, 0)).status());
        assertFalse(running.job().acquire(6, 6), "cancelled job acquires nothing");
        p.endJob(running.job());
        assertFalse(p.offerFront(taken, 0), "entry from older generation is not restored");
        release(List.of(taken));
        assertEquals(0, refs.counter.totalChunks(), "all ticket refs balanced");
        assertEquals(refs.realAdds, refs.realRemoves);
    }

    @Test
    void expiryOnPollAndSweep() {
        WorldPool p = pool(TestSettings.world(3, Duration.ofSeconds(10), 0, 5000, 32));
        fillOne(p, 0, 0, 0);
        fillOne(p, 1000, 0, 5_000);
        fillOne(p, 2000, 0, 20_000);
        List<PoolEntry> expired = new ArrayList<>();
        PoolEntry e = p.poll(12_000, expired);
        assertEquals(1, expired.size());
        assertEquals(5_000, e.location().createdAt());
        assertTrue(p.sweepExpired(25_000).isEmpty());
        assertEquals(1, p.sweepExpired(30_000).size());
        assertEquals(2, p.stats(0, false, null).totalExpired());
        assertEquals(0, p.size());
    }

    @Test
    void neverExpiresWithZero() {
        WorldPool p = pool(TestSettings.world(1));
        fillOne(p, 0, 0, 0);
        assertTrue(p.sweepExpired(Long.MAX_VALUE / 2).isEmpty());
        assertNotNull(p.poll(Long.MAX_VALUE / 2, new ArrayList<>()));
    }

    @Test
    void minSpacingReservation() {
        WorldPool p = pool(TestSettings.world(5, Duration.ZERO, 100, 5000, 32));
        fillOne(p, 0, 0, 0);
        WorldPool.Start a = p.nextStart(5, 0, refs);
        WorldPool.Start b = p.nextStart(5, 0, refs);
        assertFalse(p.tryReserve(a.job(), 50, 50, 100), "too close to ready entry");
        assertTrue(p.tryReserve(a.job(), 500, 0, 100));
        assertFalse(p.tryReserve(b.job(), 550, 0, 100), "too close to a concurrently reserved job");
        assertTrue(p.tryReserve(b.job(), 700, 0, 100));
        assertEquals(3, p.avoidPositions().length);
    }

    @Test
    void reloadTrimsOrClears() {
        WorldPool p = pool(TestSettings.world(3));
        fillOne(p, 0, 0, 1);
        fillOne(p, 1000, 0, 2);
        fillOne(p, 2000, 0, 3);
        WorldPool.Released trim = p.updateSettings(TestSettings.world(1));
        assertFalse(trim.cleared());
        assertEquals(2, trim.released().size());
        assertEquals(3, trim.released().get(0).location().createdAt(), "newest trimmed first");
        assertEquals(1, p.size());
        release(trim.released());

        WorldPool.Released none = p.updateSettings(TestSettings.world(1, Duration.ofHours(1), 0, 5000, 99));
        assertFalse(none.cleared(), "max-attempts/expiry change is not material");
        assertTrue(none.released().isEmpty());

        WorldPool.Released cleared = p.updateSettings(TestSettings.world(1, Duration.ZERO, 0, 9000, 32));
        assertTrue(cleared.cleared(), "area change clears");
        assertEquals(0, p.size());
        release(cleared.released());
        assertEquals(0, refs.counter.totalChunks());
    }

    @Test
    void restoresPreferredBoundedAndSkipExpired() {
        WorldPool p = pool(TestSettings.world(2, Duration.ofSeconds(10), 0, 5000, 32));
        List<PoolStore.StoredEntry> stored = List.of(
                new PoolStore.StoredEntry(worldId.toString(), "world", 1.5, 70, 1.5, 0, 0),       // expired
                new PoolStore.StoredEntry(worldId.toString(), "world", 2.5, 70, 2.5, 0, 9_000),
                new PoolStore.StoredEntry(worldId.toString(), "world", 3.5, 70, 3.5, 0, 9_500),
                new PoolStore.StoredEntry(worldId.toString(), "world", 4.5, 70, 4.5, 0, 9_800)); // over target
        assertEquals(2, p.addRestores(stored, 10_000));
        // Backoff must not block restores.
        p.recordFailure(10_000);
        WorldPool.Start s = p.nextStart(5, 10_001, refs);
        assertNotNull(s.restore());
        assertTrue(s.job().isRestore());
        assertTrue(s.job().isReserved());
        assertNotNull(p.nextStart(5, 10_001, refs).restore());
        assertNull(p.nextStart(5, 10_001, refs), "target reached");
    }

    @Test
    void backoffBlocksSearchesUntilResetOrElapsed() {
        WorldPool p = pool(TestSettings.world(3));
        assertFalse(p.recordFailure(1000));
        assertNull(p.nextStart(5, 1200, refs));
        assertNotNull(p.nextStart(5, 1500, refs));
        p.recordFailure(2000);
        p.resetBackoff();
        assertNotNull(p.nextStart(5, 2001, refs));
        assertEquals(2, p.stats(0, false, null).totalFailedSearches());
    }

    @Test
    void statsReflectState() {
        WorldPool p = pool(TestSettings.world(4));
        p.recordSearchSuccess(10, 40);
        p.recordSearchSuccess(20, 60);
        fillOne(p, 0, 0, 0);
        p.nextStart(5, 0, refs);
        PoolStats s = p.stats(9, true, "server empty");
        assertEquals("world", s.worldName());
        assertTrue(s.enabled());
        assertEquals(1, s.size());
        assertEquals(4, s.target());
        assertEquals(1, s.searching());
        assertEquals(1, s.totalFound());
        assertEquals(12.0, s.avgAttemptsPerFind(), 1e-9);
        assertEquals(44.0, s.avgMillisPerFind(), 1e-9);
        assertEquals(9, s.ticketedChunks());
        assertTrue(s.paused());
        assertEquals(0.25, s.fillRatio(), 1e-9);
    }

    @Test
    void stuckJobsFreeTheirSlotAndCommitStale() {
        WorldPool p = pool(TestSettings.world(5));
        WorldPool.Start old = p.nextStart(2, 0, refs);
        WorldPool.Start fresh = p.nextStart(2, 900, refs);
        assertNull(p.nextStart(2, 1000, refs), "both slots used");
        old.job().acquire(0, 0);

        List<PoolJob> stuck = p.removeJobsStartedBefore(500);
        assertEquals(List.of(old.job()), stuck);
        stuck.forEach(PoolJob::cancel);
        assertEquals(0, refs.counter.totalChunks(), "stuck job's tickets released");
        assertNotNull(p.nextStart(2, 1000, refs), "slot freed");
        assertFalse(p.isCurrent(old.job()));
        assertEquals(WorldPool.CommitStatus.STALE, p.commit(old.job(), loc(0, 0, 0)).status());
        assertTrue(p.isCurrent(fresh.job()));
    }

    @Test
    void cancelAbandonsTrackedLoads() {
        PoolJob job = new PoolJob(worldId, 0, false, 0, refs);
        java.util.concurrent.CompletableFuture<Object> pending = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Object> done = java.util.concurrent.CompletableFuture.completedFuture(1);
        job.trackLoad(done);
        job.trackLoad(pending);
        job.cancel();
        assertTrue(pending.isCancelled(), "queued load dropped with the job");
        assertFalse(done.isCancelled());
        java.util.concurrent.CompletableFuture<Object> late = new java.util.concurrent.CompletableFuture<>();
        job.trackLoad(late);
        assertTrue(late.isCancelled(), "loads registered after cancel are cancelled at once");
    }
}

package io.github.editid0.swiftrtp.pool;

import io.github.editid0.swiftrtp.config.WorldSettings;
import io.github.editid0.swiftrtp.search.SearchOptions;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-safe state of one world's pool: ready entries (head = next to serve, i.e. oldest or restored), in-progress
 * jobs, persisted entries waiting to be restored, statistics and failure backoff. Pure (no Bukkit calls): it never
 * touches tickets itself but hands entries/jobs that must be released back to the caller, so it is unit-tested.
 * <p>
 * Invariants: {@code ready <= target}; jobs never exceed the caller's {@code maxJobs}; {@code ready + jobs} never
 * exceeds the target when a job is started (a job that finds the pool full on commit is discarded).
 */
final class WorldPool {

    private final UUID worldId;
    private final String worldName;

    private WorldSettings settings;
    private String fingerprint;

    private final ArrayDeque<PoolEntry> ready = new ArrayDeque<>();
    private final Set<PoolJob> jobs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<PoolStore.StoredEntry> pendingRestores = new ArrayDeque<>();
    private int generation;

    private final FailureBackoff backoff = new FailureBackoff();
    private final MovingAverage avgAttempts = new MovingAverage(0.2);
    private final MovingAverage avgMillis = new MovingAverage(0.2);
    private long totalFound;
    private long totalServed;
    private long totalFailed;
    private long totalExpired;

    WorldPool(UUID worldId, String worldName, WorldSettings settings) {
        this.worldId = worldId;
        this.worldName = worldName;
        this.settings = settings;
        this.fingerprint = SettingsFingerprint.of(settings);
    }

    UUID worldId() {
        return worldId;
    }

    String worldName() {
        return worldName;
    }

    synchronized WorldSettings settings() {
        return settings;
    }

    synchronized String fingerprint() {
        return fingerprint;
    }

    synchronized int target() {
        return Math.max(0, settings.pool().size());
    }

    synchronized int size() {
        return ready.size();
    }

    synchronized int searching() {
        return jobs.size();
    }

    synchronized int generation() {
        return generation;
    }

    private long expireMillis() {
        return settings.pool().expireAfter().toMillis();
    }

    // ---- settings / clearing --------------------------------------------------------------------------------

    /**
     * What the caller must release after a state change.
     *
     * @param released      entries whose ticket references must be released
     * @param cancelledJobs jobs that must be {@linkplain PoolJob#cancel() cancelled} (outside this lock)
     */
    record Released(boolean cleared, List<PoolEntry> released, List<PoolJob> cancelledJobs) {
        static final Released NONE = new Released(false, List.of(), List.of());
    }

    /**
     * Applies new settings: if the area/search fingerprint changed the pool is cleared (all locations may now be
     * invalid); otherwise extra entries beyond a smaller {@code size} are trimmed (newest first).
     */
    synchronized Released updateSettings(WorldSettings fresh) {
        String freshFp = SettingsFingerprint.of(fresh);
        this.settings = fresh;
        if (!freshFp.equals(fingerprint)) {
            this.fingerprint = freshFp;
            return clearLocked();
        }
        int target = target();
        List<PoolEntry> trimmed = new ArrayList<>();
        while (ready.size() > target) {
            trimmed.add(ready.pollLast());
        }
        while (pendingRestores.size() > target) {
            pendingRestores.pollLast();
        }
        return trimmed.isEmpty() ? Released.NONE : new Released(false, trimmed, List.of());
    }

    /** Drops everything (ready, pending restores) and invalidates running jobs; resets the backoff. */
    synchronized Released clear() {
        return clearLocked();
    }

    private Released clearLocked() {
        generation++;
        List<PoolEntry> released = new ArrayList<>(ready);
        ready.clear();
        pendingRestores.clear();
        List<PoolJob> cancelled = new ArrayList<>(jobs);
        backoff.reset();
        return new Released(true, released, cancelled);
    }

    // ---- refill ---------------------------------------------------------------------------------------------

    /** A job to start: {@code restore} is non-null for a restore job. */
    record Start(PoolJob job, PoolStore.@Nullable StoredEntry restore) {}

    /**
     * Atomically decides whether another job may start and registers it. Restores take precedence and ignore the
     * failure backoff; new searches respect it.
     *
     * @return null when nothing should start now
     */
    synchronized @Nullable Start nextStart(int maxJobs, long now, TicketRefs refs) {
        int room = Math.min(target() - ready.size() - jobs.size(), Math.max(1, maxJobs) - jobs.size());
        if (room <= 0) {
            return null;
        }
        PoolStore.StoredEntry restore = null;
        while (!pendingRestores.isEmpty()) {
            PoolStore.StoredEntry candidate = pendingRestores.pollFirst();
            long exp = expireMillis();
            if (exp > 0 && now - candidate.createdAt >= exp) {
                continue;
            }
            restore = candidate;
            break;
        }
        if (restore == null && !backoff.canAttempt(now)) {
            return null;
        }
        PoolJob job = new PoolJob(worldId, generation, restore != null, now, refs);
        if (restore != null) {
            job.reserve((int) Math.floor(restore.x), (int) Math.floor(restore.z));
        }
        jobs.add(job);
        return new Start(job, restore);
    }

    synchronized void endJob(PoolJob job) {
        jobs.remove(job);
    }

    /**
     * Unregisters jobs started before {@code cutoff} (stuck: a future that never completes must not hold a
     * {@code max-searches-per-world} slot forever). The caller cancels them; when such a job finishes later its
     * commit is {@code STALE}.
     */
    synchronized List<PoolJob> removeJobsStartedBefore(long cutoff) {
        List<PoolJob> out = null;
        for (Iterator<PoolJob> it = jobs.iterator(); it.hasNext(); ) {
            PoolJob j = it.next();
            if (j.startedAt() < cutoff) {
                it.remove();
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(j);
            }
        }
        return out == null ? List.of() : out;
    }

    synchronized boolean isCurrent(PoolJob job) {
        return job.generation() == generation && jobs.contains(job);
    }

    /** Queues persisted entries for restoration (bounded by the target, skipping expired ones). */
    synchronized int addRestores(List<PoolStore.StoredEntry> entries, long now) {
        int added = 0;
        long exp = expireMillis();
        for (PoolStore.StoredEntry e : entries) {
            if (pendingRestores.size() + ready.size() >= target()) {
                break;
            }
            if (exp > 0 && now - e.createdAt >= exp) {
                continue;
            }
            pendingRestores.addLast(e);
            added++;
        }
        return added;
    }

    synchronized int pendingRestores() {
        return pendingRestores.size();
    }

    /** Packed block x/z of ready entries and reserved in-progress jobs, for {@code SearchOptions.avoidNear}. */
    synchronized long[] avoidPositions() {
        long[] out = new long[ready.size() + jobs.size()];
        int i = 0;
        for (PoolEntry e : ready) {
            out[i++] = SearchOptions.pack(e.location().blockX(), e.location().blockZ());
        }
        for (PoolJob j : jobs) {
            if (j.isReserved()) {
                out[i++] = SearchOptions.pack(j.reservedX(), j.reservedZ());
            }
        }
        return i == out.length ? out : java.util.Arrays.copyOf(out, i);
    }

    /**
     * Reserves a found position for {@code job} unless it is closer than {@code spacing} blocks (euclidean, X/Z) to
     * a ready entry or another reserved job — concurrent searches cannot see each other's results, so this closes
     * that race.
     */
    synchronized boolean tryReserve(PoolJob job, int blockX, int blockZ, int spacing) {
        if (spacing > 0) {
            long limit = (long) spacing * spacing;
            for (PoolEntry e : ready) {
                if (distSq(blockX, blockZ, e.location().blockX(), e.location().blockZ()) < limit) {
                    return false;
                }
            }
            for (PoolJob j : jobs) {
                if (j != job && j.isReserved() && distSq(blockX, blockZ, j.reservedX(), j.reservedZ()) < limit) {
                    return false;
                }
            }
        }
        job.reserve(blockX, blockZ);
        return true;
    }

    private static long distSq(int x1, int z1, int x2, int z2) {
        long dx = x1 - x2;
        long dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    synchronized void recordSearchSuccess(int attempts, double millis) {
        backoff.recordSuccess();
        avgAttempts.add(attempts);
        avgMillis.add(millis);
    }

    /** @return true when a "keeps failing" warning should be logged */
    synchronized boolean recordFailure(long now) {
        totalFailed++;
        return backoff.recordFailure(now);
    }

    synchronized int consecutiveFailures() {
        return backoff.consecutiveFailures();
    }

    synchronized void resetBackoff() {
        backoff.reset();
    }

    enum CommitStatus { ADDED, STALE, FULL }

    record Commit(CommitStatus status, @Nullable PoolEntry entry) {}

    /**
     * Turns a finished job into a ready entry. On {@code STALE} (cleared/cancelled meanwhile) or {@code FULL} the
     * caller must {@linkplain PoolJob#cancel() cancel} the job to release its references. The job stays registered
     * until {@link #endJob}.
     */
    synchronized Commit commit(PoolJob job, PooledLocation location) {
        if (job.generation() != generation || !jobs.contains(job) || job.isCancelled()) {
            return new Commit(CommitStatus.STALE, null);
        }
        if (ready.size() >= target()) {
            return new Commit(CommitStatus.FULL, null);
        }
        long[] keys = job.transfer();
        if (keys == null) {
            return new Commit(CommitStatus.STALE, null);
        }
        PoolEntry entry = new PoolEntry(location, keys, generation);
        ready.addLast(entry);
        totalFound++;
        return new Commit(CommitStatus.ADDED, entry);
    }

    // ---- serving --------------------------------------------------------------------------------------------

    /**
     * Pops the next non-expired entry. Expired entries met on the way are removed, counted and added to
     * {@code expiredOut} (caller releases them).
     */
    synchronized @Nullable PoolEntry poll(long now, List<PoolEntry> expiredOut) {
        long exp = expireMillis();
        PoolEntry e;
        while ((e = ready.pollFirst()) != null) {
            if (e.isExpired(now, exp)) {
                totalExpired++;
                expiredOut.add(e);
                continue;
            }
            totalServed++;
            return e;
        }
        return null;
    }

    /**
     * Puts a taken, unused entry back at the front. Refused (caller releases) if it belongs to an older generation,
     * expired, or the pool is full.
     */
    synchronized boolean offerFront(PoolEntry entry, long now) {
        if (entry.generation() != generation || entry.isExpired(now, expireMillis()) || ready.size() >= target()) {
            return false;
        }
        ready.addFirst(entry);
        if (totalServed > 0) {
            totalServed--;
        }
        return true;
    }

    /** Removes and returns expired entries (caller releases them). */
    synchronized List<PoolEntry> sweepExpired(long now) {
        long exp = expireMillis();
        if (exp <= 0 || ready.isEmpty()) {
            return List.of();
        }
        List<PoolEntry> out = new ArrayList<>();
        for (Iterator<PoolEntry> it = ready.iterator(); it.hasNext(); ) {
            PoolEntry e = it.next();
            if (e.isExpired(now, exp)) {
                it.remove();
                out.add(e);
                totalExpired++;
            }
        }
        return out;
    }

    synchronized List<PoolEntry> readySnapshot() {
        return new ArrayList<>(ready);
    }

    synchronized List<PoolStore.StoredEntry> pendingRestoreSnapshot() {
        return new ArrayList<>(pendingRestores);
    }

    synchronized PoolStats stats(int ticketedChunks, boolean paused, @Nullable String pauseReason) {
        return new PoolStats(worldName, true, ready.size(), target(), jobs.size(), totalFound, totalServed,
                totalFailed, totalExpired, avgAttempts.value(), avgMillis.value(), ticketedChunks, paused,
                pauseReason);
    }
}

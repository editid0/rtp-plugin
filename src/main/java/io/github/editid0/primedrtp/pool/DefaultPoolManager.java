package io.github.editid0.primedrtp.pool;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.config.PluginSettings;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.github.editid0.primedrtp.search.ChunkLoader;
import io.github.editid0.primedrtp.search.SearchOptions;
import io.github.editid0.primedrtp.search.SearchResult;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Default {@link PoolManager}: one {@link WorldPool} per loaded, RTP- and pool-enabled world, refilled in the
 * background so {@link #take} is instant.
 *
 * <h2>Refill pipeline (per job)</h2>
 * <ol>
 *   <li>BACKGROUND search via {@link io.github.editid0.primedrtp.search.LocationSearcher} with the positions of ready
 *       and in-progress entries as {@code avoidNear} (min-spacing); a found spot is then reserved atomically so two
 *       concurrent searches cannot pick neighbouring spots. Restore jobs (persisted entries) skip the search.</li>
 *   <li>Pregenerate every chunk within {@code pool.pregen-radius} (BACKGROUND, generate=true, nearest first) through
 *       the throttled {@link ChunkLoader}; chunks within {@code keep-loaded-radius} get a reference-counted plugin
 *       ticket as soon as they are loaded.</li>
 *   <li>Re-validate with {@code isStillSafe} on the owning region thread (neighbour generation may have placed trees,
 *       structures, lava...).</li>
 *   <li>Commit: the entry becomes ready only now. Stale (pool cleared meanwhile) or surplus jobs release their
 *       tickets.</li>
 * </ol>
 * Search failures back off exponentially per world (0.5 s .. 30 s) with a throttled warning.
 *
 * <h2>Threading</h2>
 * Bookkeeping lives in synchronised/concurrent structures, so every public method is callable from any thread.
 * World/chunk/ticket access only happens on the owning region thread ({@link ChunkTicketTracker}, validation).
 * Timers run on the global region; autosave IO runs async; the final save in {@link #stop()} is synchronous.
 */
public final class DefaultPoolManager implements PoolManager {

    /** Ticks a released entry keeps its tickets so the arriving player's own tickets take over (5 s). */
    private static final long RELEASE_DELAY_TICKS = 100;
    /** Taken entries never released/restored for this long are freed (consumer bug safety net). */
    private static final long HELD_LEAK_MILLIS = 10 * 60_000L;
    /**
     * A job running this long (while background loading was not paused) is considered stuck and is dropped, so a
     * future that never completes cannot block refilling forever.
     */
    static final long JOB_TIMEOUT_MILLIS = 10 * 60_000L;
    /** Upper bound on persisted entries kept for worlds that are not loaded. */
    private static final int MAX_BACKLOG = 10_000;

    private final PrimedRTP plugin;
    private final Map<UUID, WorldPool> pools = new ConcurrentHashMap<>();
    /** Entries handed out by {@link #take} and not yet released/restored, by id. */
    private final Map<Long, Held> held = new ConcurrentHashMap<>();
    /** Persisted entries for worlds without a pool yet (not loaded), by world UUID. */
    private final Map<UUID, List<PoolStore.StoredEntry>> backlog = new ConcurrentHashMap<>();
    /** Settings fingerprints stored alongside {@link #backlog}, by world UUID. */
    private final Map<UUID, String> backlogFingerprints = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    private final Object lifecycle = new Object();
    private final Object storeLock = new Object();

    private volatile @Nullable ChunkTicketTracker tickets;
    private volatile boolean running;
    /** True once the startup delay elapsed and the refill timer runs. */
    private volatile boolean refillActive;
    /** True once the persisted file was read (or persistence is off) — never overwrite unread data. */
    private volatile boolean storeLoaded;
    /** Last time (ms) the refill timer saw the chunk loader paused; the stuck-job watchdog waits after pauses. */
    private volatile long lastPausedAt;

    private @Nullable Listener listener;
    private @Nullable ScheduledTask startupTask;
    private @Nullable ScheduledTask refillTask;
    private @Nullable ScheduledTask autosaveTask;
    private @Nullable Duration refillInterval;
    private @Nullable Duration autosaveInterval;

    private record Held(UUID worldId, PoolEntry entry, long takenAt) {}

    /** Expected, already-handled pipeline abort (logged at debug level only). */
    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message, null, false, false);
        }
    }

    /** The location failed re-validation or its pipeline errored: counts towards backoff. */
    private static final class Failed extends RuntimeException {
        Failed(String message) {
            super(message, null, false, false);
        }
    }

    public DefaultPoolManager(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    // =========================================================================================================
    // lifecycle
    // =========================================================================================================

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            running = true;
            refillActive = false;
            tickets = new ChunkTicketTracker(plugin);

            Listener l = new WorldListener();
            plugin.getServer().getPluginManager().registerEvents(l, plugin);
            listener = l;

            for (World world : Bukkit.getWorlds()) {
                syncPool(world);
            }

            PluginSettings s = plugin.settings();
            if (s.storage().persistPools()) {
                storeLoaded = false;
                plugin.scheduling().async(this::loadStore);
            } else {
                storeLoaded = true;
            }
            scheduleAutosave(s);
            startupTask = plugin.scheduling().globalLater(this::beginRefilling, ticks(s.performance().startupDelay()));
            plugin.debug("pool manager started, " + pools.size() + " pool(s), refill begins in "
                    + s.performance().startupDelay().toMillis() + "ms");
        }
    }

    @Override
    public void reload() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            PluginSettings s = plugin.settings();
            for (World world : Bukkit.getWorlds()) {
                syncPool(world);
            }
            // Pools whose world vanished without an unload event (defensive).
            for (UUID id : List.copyOf(pools.keySet())) {
                if (Bukkit.getWorld(id) == null) {
                    removePool(id, false);
                }
            }
            if (refillActive && !Objects.equals(refillInterval, s.performance().refillCheckInterval())) {
                scheduleRefillTimer(s);
            }
            if (!Objects.equals(autosaveInterval, effectiveAutosave(s))) {
                scheduleAutosave(s);
            }
            // Enabling persist-pools at runtime does not read the old file (it would be stale); the next save
            // writes the current pools. A load already in flight from start() completes normally.
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            boolean wasRunning = running;
            running = false;
            refillActive = false;
            cancel(startupTask);
            cancel(refillTask);
            cancel(autosaveTask);
            startupTask = refillTask = autosaveTask = null;
            refillInterval = autosaveInterval = null;
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
            if (!wasRunning) {
                return;
            }

            PluginSettings s = plugin.settings();
            if (s != null && s.storage().persistPools() && storeLoaded) {
                try {
                    synchronized (storeLock) {
                        PoolStore.Snapshot snap = snapshot();
                        PoolStore.write(storeFile(), snap);
                        plugin.debug("saved " + snap.entries.size() + " pooled location(s) on shutdown");
                    }
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save pools.json on shutdown", e);
                }
            }

            ChunkTicketTracker t = tickets;
            if (t != null) {
                t.close(); // no further ticket changes; jobs cancelled below become no-ops
            }
            for (WorldPool pool : pools.values()) {
                WorldPool.Released r = pool.clear();
                r.cancelledJobs().forEach(PoolJob::cancel);
            }
            pools.clear();
            held.clear();
            backlog.clear();
            backlogFingerprints.clear();
            try {
                for (World world : Bukkit.getWorlds()) {
                    world.removePluginChunkTickets(plugin);
                }
            } catch (RuntimeException e) {
                plugin.debug("removing plugin chunk tickets on shutdown failed (Paper clears them anyway): " + e);
            }
        }
    }

    private void beginRefilling() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            refillActive = true;
            startupTask = null;
            scheduleRefillTimer(plugin.settings());
        }
        plugin.debug("startup delay over, pool refilling active");
    }

    private void scheduleRefillTimer(PluginSettings s) {
        cancel(refillTask);
        Duration interval = s.performance().refillCheckInterval();
        refillInterval = interval;
        long period = ticks(interval);
        refillTask = plugin.scheduling().globalTimer(t -> tick(), 1L, period);
    }

    private static @Nullable Duration effectiveAutosave(PluginSettings s) {
        Duration d = s.storage().autosaveInterval();
        return s.storage().persistPools() && d != null && !d.isZero() && !d.isNegative() ? d : null;
    }

    private void scheduleAutosave(PluginSettings s) {
        cancel(autosaveTask);
        autosaveTask = null;
        Duration d = effectiveAutosave(s);
        autosaveInterval = d;
        if (d != null) {
            autosaveTask = plugin.scheduling().asyncTimer(t -> autosave(), d, d);
        }
    }

    private static void cancel(@Nullable ScheduledTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // already cancelled / scheduler gone
            }
        }
    }

    private static long ticks(@Nullable Duration d) {
        return d == null ? 1L : Math.max(1L, d.toMillis() / 50L);
    }

    // =========================================================================================================
    // pool registry
    // =========================================================================================================

    private Optional<WorldSettings> desiredSettings(World world) {
        PluginSettings s = plugin.settings();
        if (s == null) {
            return Optional.empty();
        }
        return s.world(world.getName())
                .filter(WorldSettings::enabled)
                .filter(ws -> ws.pool().enabled() && ws.pool().size() > 0);
    }

    /** Creates, updates or removes the pool of a loaded world to match current settings. */
    private void syncPool(World world) {
        UUID id = world.getUID();
        Optional<WorldSettings> desired = desiredSettings(world);
        WorldPool existing = pools.get(id);
        if (desired.isEmpty()) {
            if (existing != null) {
                removePool(id, false);
                plugin.debug("pool for " + world.getName() + " disabled");
            }
            return;
        }
        WorldSettings ws = desired.get();
        if (existing == null) {
            WorldPool pool = new WorldPool(id, world.getName(), ws);
            pools.put(id, pool);
            claimBacklog(pool);
            plugin.debug("pool for " + world.getName() + " created (target " + pool.target() + ")");
            requestFill(pool);
            return;
        }
        WorldPool.Released r = existing.updateSettings(ws);
        releaseNow(existing.worldId(), r);
        if (r.cleared()) {
            plugin.debug("pool for " + world.getName() + " cleared: area/search settings changed ("
                    + r.released().size() + " dropped)");
        } else if (!r.released().isEmpty()) {
            plugin.debug("pool for " + world.getName() + " trimmed by " + r.released().size() + " to "
                    + existing.target());
        }
        requestFill(existing);
    }

    /**
     * Removes a pool and frees its tickets.
     *
     * @param keepForLater move ready entries to the persistence backlog (world unloading, may come back)
     */
    private void removePool(UUID id, boolean keepForLater) {
        WorldPool pool = pools.remove(id);
        if (pool == null) {
            return;
        }
        if (keepForLater) {
            List<PoolStore.StoredEntry> keep = new ArrayList<>();
            for (PoolEntry e : pool.readySnapshot()) {
                keep.add(toStored(e.location()));
            }
            keep.addAll(pool.pendingRestoreSnapshot());
            if (!keep.isEmpty() && backlogSize() + keep.size() <= MAX_BACKLOG) {
                backlog.put(id, keep);
                backlogFingerprints.put(id, pool.fingerprint());
            }
        }
        releaseNow(id, pool.clear());
        // Taken entries for this world can no longer be restored; free them.
        for (Iterator<Held> it = held.values().iterator(); it.hasNext(); ) {
            Held h = it.next();
            if (h.worldId().equals(id)) {
                it.remove();
                releaseEntryNow(h.worldId(), h.entry());
            }
        }
    }

    private int backlogSize() {
        int n = 0;
        for (List<PoolStore.StoredEntry> l : backlog.values()) {
            n += l.size();
        }
        return n;
    }

    /** Moves persisted entries for this pool's world into its restore queue if the settings still match. */
    private void claimBacklog(WorldPool pool) {
        List<PoolStore.StoredEntry> entries = backlog.remove(pool.worldId());
        String fp = backlogFingerprints.remove(pool.worldId());
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (fp != null && !fp.equals(pool.fingerprint())) {
            plugin.debug("discarding " + entries.size() + " persisted location(s) for " + pool.worldName()
                    + ": area/search settings changed");
            return;
        }
        int added = pool.addRestores(entries, System.currentTimeMillis());
        plugin.debug("queued " + added + " persisted location(s) for re-validation in " + pool.worldName());
        requestFill(pool);
    }

    private final class WorldListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldLoad(WorldLoadEvent event) {
            if (running) {
                syncPool(event.getWorld());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onWorldUnload(WorldUnloadEvent event) {
            if (!running) {
                return;
            }
            UUID id = event.getWorld().getUID();
            removePool(id, true);
            ChunkTicketTracker t = tickets;
            if (t != null) {
                int forgotten = t.forgetWorld(id);
                plugin.debug("world " + event.getWorld().getName() + " unloading: pool dropped, " + forgotten
                        + " ticketed chunk(s) forgotten");
            }
        }
    }

    // =========================================================================================================
    // PoolManager API
    // =========================================================================================================

    @Override
    public Optional<PooledLocation> take(World world) {
        WorldPool pool = pools.get(world.getUID());
        if (pool == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        List<PoolEntry> expired = new ArrayList<>(0);
        PoolEntry entry = pool.poll(now, expired);
        for (PoolEntry e : expired) {
            releaseEntryNow(pool.worldId(), e);
            plugin.debug("expired " + describe(e.location()) + " skipped on take");
        }
        requestFill(pool);
        if (entry == null) {
            plugin.debug("take from " + world.getName() + ": pool empty");
            return Optional.empty();
        }
        held.put(entry.location().id(), new Held(pool.worldId(), entry, now));
        plugin.debug("served " + describe(entry.location()) + " (" + pool.size() + "/" + pool.target() + " left)");
        return Optional.of(entry.location());
    }

    @Override
    public void release(PooledLocation location) {
        Held h = held.remove(location.id());
        if (h == null) {
            plugin.debug("release of #" + location.id() + " ignored (not held)");
            return;
        }
        if (running) {
            try {
                plugin.scheduling().globalLater(() -> releaseEntryNow(h.worldId(), h.entry()), RELEASE_DELAY_TICKS);
                return;
            } catch (RuntimeException e) {
                // scheduler unavailable (disabling): release immediately
            }
        }
        releaseEntryNow(h.worldId(), h.entry());
    }

    @Override
    public void restore(PooledLocation location) {
        Held h = held.remove(location.id());
        if (h == null) {
            plugin.debug("restore of #" + location.id() + " ignored (not held)");
            return;
        }
        WorldPool pool = pools.get(h.worldId());
        if (pool != null && pool.offerFront(h.entry(), System.currentTimeMillis())) {
            plugin.debug("restored " + describe(location) + " to the front of the pool");
            return;
        }
        releaseEntryNow(h.worldId(), h.entry());
        plugin.debug("restore of " + describe(location) + " refused (expired/full/cleared), released");
        if (pool != null) {
            requestFill(pool);
        }
    }

    @Override
    public int size(World world) {
        WorldPool pool = pools.get(world.getUID());
        return pool == null ? 0 : pool.size();
    }

    @Override
    public PoolStats stats(World world) {
        WorldPool pool = pools.get(world.getUID());
        int ticketed = ticketedChunks(world.getUID());
        String reason = pauseReason();
        if (pool != null) {
            return pool.stats(ticketed, reason != null, reason);
        }
        return new PoolStats(world.getName(), false, 0, 0, 0, 0, 0, 0, 0, 0, 0, ticketed, reason != null, reason);
    }

    @Override
    public List<PoolStats> allStats() {
        String reason = pauseReason();
        List<PoolStats> out = new ArrayList<>(pools.size());
        for (WorldPool pool : pools.values()) {
            out.add(pool.stats(ticketedChunks(pool.worldId()), reason != null, reason));
        }
        out.sort(Comparator.comparing(PoolStats::worldName));
        return out;
    }

    @Override
    public void clear(World world) {
        WorldPool pool = pools.get(world.getUID());
        if (pool == null) {
            return;
        }
        WorldPool.Released r = pool.clear();
        releaseNow(pool.worldId(), r);
        plugin.debug("pool for " + world.getName() + " cleared by request (" + r.released().size() + " dropped, "
                + r.cancelledJobs().size() + " job(s) cancelled)");
        requestFill(pool);
    }

    @Override
    public void refillNow(World world) {
        WorldPool pool = pools.get(world.getUID());
        if (pool == null) {
            return;
        }
        pool.resetBackoff();
        requestFill(pool);
    }

    private int ticketedChunks(UUID worldId) {
        ChunkTicketTracker t = tickets;
        return t == null ? 0 : t.chunkCount(worldId);
    }

    private @Nullable String pauseReason() {
        ChunkLoader loader = plugin.chunkLoader();
        if (loader != null && loader.isPaused()) {
            String r = loader.pauseReason();
            return r == null ? "paused" : r;
        }
        if (running && !refillActive) {
            return "startup delay";
        }
        return null;
    }

    // =========================================================================================================
    // refill
    // =========================================================================================================

    /** Global timer: expiry sweep, leak sweep, refill. */
    private void tick() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        ChunkLoader loader = plugin.chunkLoader();
        if (lastPausedAt == 0 || (loader != null && loader.isPaused())) {
            lastPausedAt = now;
        }
        boolean watchdog = now - lastPausedAt > JOB_TIMEOUT_MILLIS;
        for (WorldPool pool : pools.values()) {
            try {
                for (PoolEntry e : pool.sweepExpired(now)) {
                    releaseEntryNow(pool.worldId(), e);
                    plugin.debug("expired " + describe(e.location()));
                }
                if (watchdog) {
                    List<PoolJob> stuck = pool.removeJobsStartedBefore(now - JOB_TIMEOUT_MILLIS);
                    for (PoolJob j : stuck) {
                        j.cancel();
                    }
                    if (!stuck.isEmpty()) {
                        plugin.getLogger().warning(pool.worldName() + ": " + stuck.size() + " pool job(s) did not "
                                + "finish within " + (JOB_TIMEOUT_MILLIS / 60_000) + " minutes; dropped them");
                    }
                }
                fill(pool);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Pool maintenance failed for " + pool.worldName(), e);
            }
        }
        for (Iterator<Held> it = held.values().iterator(); it.hasNext(); ) {
            Held h = it.next();
            if (now - h.takenAt() > HELD_LEAK_MILLIS) {
                it.remove();
                releaseEntryNow(h.worldId(), h.entry());
                plugin.getLogger().warning("Pooled location " + describe(h.entry().location())
                        + " was taken but never released; freed its chunk tickets");
            }
        }
    }

    /** Asks for a refill soon (on the global region, so callers never recurse into the pipeline). */
    private void requestFill(WorldPool pool) {
        if (!running || !refillActive) {
            return;
        }
        try {
            plugin.scheduling().global(() -> fill(pool));
        } catch (RuntimeException e) {
            plugin.debug("refill request dropped: " + e);
        }
    }

    /** Starts as many jobs as the pool needs and the limits allow. Thread-safe. */
    private void fill(WorldPool pool) {
        if (!running || !refillActive || pools.get(pool.worldId()) != pool) {
            return;
        }
        ChunkLoader loader = plugin.chunkLoader();
        ChunkTicketTracker t = tickets;
        World world = Bukkit.getWorld(pool.worldId());
        if (world == null || t == null || loader == null || loader.isPaused()) {
            return;
        }
        int maxJobs = Math.max(1, plugin.settings().performance().maxSearchesPerWorld());
        WorldPool.Start start;
        while ((start = pool.nextStart(maxJobs, System.currentTimeMillis(), t)) != null) {
            runJob(pool, world, pool.settings(), start);
        }
    }

    private void runJob(WorldPool pool, World world, WorldSettings ws, WorldPool.Start start) {
        PoolJob job = start.job();
        PoolStore.StoredEntry restore = start.restore();
        final long createdAt;
        final int[] attempts = {0};
        final long[] searchNanos = {0};
        CompletableFuture<Location> located;
        try {
            if (restore != null) {
                createdAt = restore.createdAt;
                located = CompletableFuture.completedFuture(
                        new Location(world, restore.x, restore.y, restore.z, restore.yaw, 0f));
            } else {
                createdAt = System.currentTimeMillis();
                int spacing = Math.max(0, ws.pool().minSpacing());
                long[] avoid = spacing > 0 ? pool.avoidPositions() : null;
                if (avoid != null && avoid.length == 0) {
                    avoid = null;
                }
                SearchOptions options = new SearchOptions(ChunkLoader.Priority.BACKGROUND, 0, avoid, spacing);
                located = plugin.searcher().search(world, ws, options).thenApply(result -> {
                    attempts[0] = result.attempts();
                    searchNanos[0] = result.elapsedNanos();
                    return onSearched(pool, job, result, spacing);
                });
            }
        } catch (RuntimeException e) {
            finish(pool, world, job, null, e, 0, 0, restore != null);
            return;
        }
        final boolean restoring = restore != null;
        located.thenCompose(loc -> prepare(world, job, loc, ws, restoring))
                .thenCompose(loc -> validate(world, job, loc, ws))
                .whenComplete((loc, error) -> {
                    PooledLocation pooled = null;
                    if (error == null && loc != null) {
                        pooled = new PooledLocation(ids.incrementAndGet(), world.getUID(), world.getName(),
                                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), createdAt);
                    }
                    finish(pool, world, job, pooled, error, attempts[0], searchNanos[0], restore != null);
                });
    }

    /** Interprets a search result; returns the location or throws {@link Skip}/{@link Failed}. */
    private Location onSearched(WorldPool pool, PoolJob job, SearchResult result, int spacing) {
        if (!result.success()) {
            if (result.failure() == SearchResult.Failure.CANCELLED) {
                throw new Skip("search cancelled");
            }
            throw new Failed("search failed: " + result.failure() + " after " + result.attempts() + " attempts");
        }
        pool.recordSearchSuccess(result.attempts(), result.elapsedNanos() / 1_000_000.0);
        if (job.isCancelled()) {
            throw new Skip("pool cleared during search");
        }
        Location loc = Objects.requireNonNull(result.location());
        if (!pool.tryReserve(job, loc.getBlockX(), loc.getBlockZ(), spacing)) {
            throw new Skip("found spot " + loc.getBlockX() + "," + loc.getBlockZ()
                    + " too close to a concurrently found one");
        }
        return loc;
    }

    /**
     * Pregenerates the pregen square and tickets the keep-loaded square. Completes with {@code loc}. Restored
     * (persisted) entries were already pregenerated when first found, so only the keep-loaded square is loaded —
     * this makes pools refill several times faster after a restart.
     */
    private CompletableFuture<Location> prepare(World world, PoolJob job, Location loc, WorldSettings ws,
                                                boolean restoring) {
        if (job.isCancelled()) {
            throw new Skip("pool cleared");
        }
        int keep = Math.max(0, ws.pool().keepLoadedRadius());
        int pregen = restoring ? keep : Math.max(keep, ws.pool().pregenRadius());
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        ChunkLoader loader = plugin.chunkLoader();
        boolean generate = generates(ws);
        long[] keys = ChunkArea.square(cx, cz, pregen);
        CompletableFuture<?>[] loads = new CompletableFuture<?>[keys.length];
        for (int i = 0; i < keys.length; i++) {
            int x = ChunkArea.keyX(keys[i]);
            int z = ChunkArea.keyZ(keys[i]);
            CompletableFuture<Chunk> f = loader.load(world, x, z, generate, ChunkLoader.Priority.BACKGROUND);
            job.trackLoad(f);
            CompletableFuture<?> step = ChunkArea.chebyshev(keys[i], cx, cz) <= keep
                    // Ticket as soon as loaded so it cannot unload while the rest of the area generates.
                    ? f.thenAccept(chunk -> job.acquire(x, z))
                    : f;
            // GENERATED_ONLY never generates terrain: missing neighbours are simply skipped (and not ticketed).
            loads[i] = generate ? step : step.handle((v, ex) -> {
                if (ex != null && !(unwrap(ex) instanceof ChunkLoader.ChunkNotGeneratedException)) {
                    throw ex instanceof CompletionException ce ? ce : new CompletionException(ex);
                }
                return null;
            });
        }
        return CompletableFuture.allOf(loads).thenApply(v -> loc);
    }

    /** Re-checks safety on the owning region thread after neighbour generation. */
    private CompletableFuture<Location> validate(World world, PoolJob job, Location loc, WorldSettings ws) {
        if (job.isCancelled()) {
            throw new Skip("pool cleared");
        }
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        CompletableFuture<Chunk> load = plugin.chunkLoader().load(world, cx, cz, generates(ws),
                ChunkLoader.Priority.BACKGROUND);
        job.trackLoad(load);
        return load
                .thenCompose(chunk -> plugin.scheduling().supplyOnRegion(world, cx, cz,
                        () -> world.isChunkLoaded(cx, cz) && plugin.searcher().isStillSafe(loc, ws)))
                .thenApply(safe -> {
                    if (!safe) {
                        throw new Failed("location " + loc.getBlockX() + "," + loc.getBlockY() + ","
                                + loc.getBlockZ() + " no longer safe after pregeneration");
                    }
                    return loc;
                });
    }

    /** {@code chunk-preference: generated_only} promises never to generate terrain, pregeneration included. */
    private static boolean generates(WorldSettings ws) {
        return ws.search().chunkPreference() != WorldSettings.ChunkPreference.GENERATED_ONLY;
    }

    private void finish(WorldPool pool, World world, PoolJob job, @Nullable PooledLocation pooled,
                        @Nullable Throwable error, int attempts, long searchNanos, boolean restore) {
        try {
            if (pooled != null) {
                WorldPool.Commit commit = pool.commit(job, pooled);
                switch (commit.status()) {
                    case ADDED -> {
                        PoolEntry e = Objects.requireNonNull(commit.entry());
                        long totalMs = (System.currentTimeMillis() - job.startedAt());
                        plugin.debug((restore ? "restored " : "added ") + describe(pooled) + " to pool ("
                                + pool.size() + "/" + pool.target() + "), took " + totalMs + "ms total"
                                + (restore ? "" : ", search " + (searchNanos / 1_000_000) + "ms/" + attempts
                                + " attempts") + ", " + e.ticketChunks().length + " ticket ref(s), "
                                + ticketedChunks(pool.worldId()) + " chunk(s) ticketed in " + world.getName());
                    }
                    case STALE -> {
                        job.cancel();
                        plugin.debug("discarded " + describe(pooled) + ": pool cleared meanwhile");
                    }
                    case FULL -> {
                        job.cancel();
                        plugin.debug("discarded " + describe(pooled) + ": pool already full");
                    }
                }
                return;
            }
            job.cancel();
            Throwable cause = unwrap(error);
            if (cause instanceof Skip skip) {
                plugin.debug(world.getName() + ": " + (restore ? "restore" : "refill") + " skipped: "
                        + skip.getMessage());
            } else if (cause instanceof CancellationException) {
                plugin.debug(world.getName() + ": refill job cancelled");
            } else if (!pool.isCurrent(job)) {
                // Cleared or dropped by the watchdog meanwhile: its failure says nothing about the current settings.
                plugin.debug(world.getName() + ": stale pool job ended: " + describeError(cause));
            } else if (restore) {
                // A persisted spot that is gone/unsafe is expected after world edits; no backoff.
                plugin.debug(world.getName() + ": persisted location dropped: " + describeError(cause));
            } else {
                boolean warn = pool.recordFailure(System.currentTimeMillis());
                plugin.debug(world.getName() + ": refill failed (" + pool.consecutiveFailures()
                        + " in a row): " + describeError(cause));
                if (!(cause instanceof Failed)) {
                    plugin.getLogger().log(Level.FINE, "Pool refill error in " + world.getName(), cause);
                }
                if (warn) {
                    plugin.getLogger().warning(world.getName() + ": " + pool.consecutiveFailures()
                            + " consecutive failed pool searches — check search settings (area, biomes, Y range,"
                            + " unsafe blocks) for this world");
                }
            }
        } catch (RuntimeException e) {
            job.cancel();
            plugin.getLogger().log(Level.WARNING, "Pool job bookkeeping failed for " + world.getName(), e);
        } finally {
            pool.endJob(job);
            requestFill(pool);
        }
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String describeError(@Nullable Throwable t) {
        if (t == null) {
            return "unknown";
        }
        if (t instanceof Failed || t instanceof ChunkLoader.ChunkNotGeneratedException) {
            return t.getMessage();
        }
        return t.toString();
    }

    // =========================================================================================================
    // tickets
    // =========================================================================================================

    private void releaseEntryNow(UUID worldId, PoolEntry entry) {
        ChunkTicketTracker t = tickets;
        if (t != null) {
            t.releaseAll(worldId, entry.ticketChunks());
        }
    }

    private void releaseNow(UUID worldId, WorldPool.Released r) {
        for (PoolEntry e : r.released()) {
            releaseEntryNow(worldId, e);
        }
        for (PoolJob j : r.cancelledJobs()) {
            j.cancel();
        }
    }

    // =========================================================================================================
    // persistence
    // =========================================================================================================

    private Path storeFile() {
        return plugin.getDataFolder().toPath().resolve("data").resolve("pools.json");
    }

    /** Async: reads pools.json and hands entries to pools (or the backlog for worlds not loaded). */
    private void loadStore() {
        PoolStore.Snapshot snap;
        try {
            synchronized (storeLock) {
                snap = PoolStore.read(storeFile());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read pools.json, pools start empty: " + e.getMessage());
            snap = new PoolStore.Snapshot();
        }
        Map<UUID, List<PoolStore.StoredEntry>> byWorld = new HashMap<>();
        for (PoolStore.StoredEntry e : snap.entries) {
            try {
                byWorld.computeIfAbsent(UUID.fromString(e.worldId), k -> new ArrayList<>()).add(e);
            } catch (IllegalArgumentException ignored) {
                // malformed UUID: drop entry
            }
        }
        int total = 0;
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            for (Map.Entry<UUID, List<PoolStore.StoredEntry>> en : byWorld.entrySet()) {
                if (total + en.getValue().size() > MAX_BACKLOG) {
                    break;
                }
                total += en.getValue().size();
                backlog.put(en.getKey(), en.getValue());
                String fp = snap.fingerprints.get(en.getKey().toString());
                if (fp != null) {
                    backlogFingerprints.put(en.getKey(), fp);
                }
            }
            storeLoaded = true;
            for (WorldPool pool : pools.values()) {
                claimBacklog(pool);
            }
        }
        plugin.debug("loaded " + total + " persisted pool location(s)");
    }

    private void autosave() {
        if (!running || !storeLoaded) {
            return;
        }
        try {
            synchronized (storeLock) {
                if (!running) {
                    return;
                }
                PoolStore.Snapshot snap = snapshot();
                PoolStore.write(storeFile(), snap);
                plugin.debug("autosaved " + snap.entries.size() + " pooled location(s)");
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Pool autosave failed: " + e);
        }
    }

    /** Ready + not-yet-restored entries of all pools, plus the backlog of unloaded worlds. Thread-safe. */
    private PoolStore.Snapshot snapshot() {
        List<PoolStore.StoredEntry> entries = new ArrayList<>();
        Map<String, String> fingerprints = new HashMap<>();
        for (WorldPool pool : pools.values()) {
            fingerprints.put(pool.worldId().toString(), pool.fingerprint());
            for (PoolEntry e : pool.readySnapshot()) {
                entries.add(toStored(e.location()));
            }
            entries.addAll(pool.pendingRestoreSnapshot());
        }
        for (Map.Entry<UUID, List<PoolStore.StoredEntry>> en : backlog.entrySet()) {
            if (pools.containsKey(en.getKey())) {
                continue;
            }
            entries.addAll(en.getValue());
            String fp = backlogFingerprints.get(en.getKey());
            if (fp != null) {
                fingerprints.put(en.getKey().toString(), fp);
            }
        }
        return new PoolStore.Snapshot(System.currentTimeMillis(), fingerprints, entries);
    }

    private static PoolStore.StoredEntry toStored(PooledLocation l) {
        return new PoolStore.StoredEntry(l.worldId().toString(), l.worldName(), l.x(), l.y(), l.z(), l.yaw(),
                l.createdAt());
    }

    private static String describe(PooledLocation l) {
        return "#" + l.id() + " " + l.worldName() + " " + l.blockX() + "," + l.blockY() + "," + l.blockZ();
    }
}

package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.api.LocationFilter;
import io.github.editid0.swiftrtp.config.PluginSettings;
import io.github.editid0.swiftrtp.config.WorldSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.BiomeFilter;
import io.github.editid0.swiftrtp.config.WorldSettings.ChunkPreference;
import io.github.editid0.swiftrtp.config.WorldSettings.SearchSettings;
import io.github.editid0.swiftrtp.search.SafetyChecker.Mode;
import io.github.editid0.swiftrtp.search.SafetyChecker.Rules;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Default {@link LocationSearcher}.
 * <p>
 * Pipeline per search:
 * <ol>
 *   <li>World snapshot (spawn, border, players) read on the global region thread, cached for
 *       {@value #SNAPSHOT_TTL_MS} ms (URGENT searches may use a stale snapshot up to {@value #SNAPSHOT_STALE_MS} ms old
 *       while a refresh runs, so they never wait a tick for it).</li>
 *   <li>Area resolution ({@link WorldArea}) — empty area → {@code INVALID_AREA}.</li>
 *   <li>Sampling ({@link AreaSampler}) + cheap pre-filters on an async thread: border, {@code avoidNear} spacing,
 *       player distance and biome estimate via the world's biome provider (no chunk load). Chunk preference is
 *       enforced by the load itself (generate=false; UNGENERATED_ONLY probes first).
 *       Pre-filter rejections do not count as attempts but are capped at {@value #PREFILTER_FACTOR}×max-attempts.</li>
 *   <li>Chunk load through {@link ChunkLoader} with the search's priority; on the owning thread
 *       {@link SafetyChecker} finds a standing spot, then the real biome and every {@link LocationFilter} are
 *       checked.</li>
 * </ol>
 * URGENT searches evaluate up to {@value #URGENT_PARALLELISM} candidates concurrently (first success wins);
 * BACKGROUND searches are strictly sequential. The returned future never completes exceptionally.
 */
public final class DefaultLocationSearcher implements LocationSearcher {

    static final int URGENT_PARALLELISM = 3;
    static final int PREFILTER_FACTOR = 50;
    static final long SNAPSHOT_TTL_MS = 2_000;
    static final long SNAPSHOT_STALE_MS = 30_000;
    /** CAVE scans with no configured max-y stay this many blocks below the logical height (nether bedrock roof). */
    static final int CAVE_ROOF_MARGIN = 6;

    private final SwiftRTP plugin;
    private final Map<UUID, WorldSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<WorldSnapshot>> refreshing = new ConcurrentHashMap<>();
    private final Map<BiomeFilter, BiomeMatcher> matchers = new ConcurrentHashMap<>();
    /** Worlds whose biome provider threw during the pre-check (pre-check disabled for them). */
    private final Set<UUID> biomePrecheckBroken = ConcurrentHashMap.newKeySet();
    private final Set<Run> active = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedErrors = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public DefaultLocationSearcher(SwiftRTP plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void start() {
        stopped = false;
    }

    @Override
    public void reload() {
        matchers.clear();
        snapshots.clear();
    }

    @Override
    public void stop() {
        stopped = true;
        for (Run run : List.copyOf(active)) {
            run.finish(null, SearchResult.Failure.CANCELLED);
        }
        active.clear();
        snapshots.clear();
        refreshing.clear();
    }

    // ---- search --------------------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<SearchResult> search(World world, WorldSettings settings, SearchOptions options) {
        long start = System.nanoTime();
        try {
            if (stopped) {
                return CompletableFuture.completedFuture(
                        SearchResult.failed(SearchResult.Failure.CANCELLED, 0, System.nanoTime() - start));
            }
            if (world == null || settings == null || !settings.enabled() || Bukkit.getWorld(world.getUID()) == null) {
                return CompletableFuture.completedFuture(
                        SearchResult.failed(SearchResult.Failure.WORLD_UNAVAILABLE, 0, System.nanoTime() - start));
            }
            Run run = new Run(world, settings, options == null ? SearchOptions.background() : options, start);
            active.add(run);
            if (stopped) {
                run.finish(null, SearchResult.Failure.CANCELLED);
                return run.future;
            }
            boolean urgent = run.options.priority() == ChunkLoader.Priority.URGENT;
            snapshot(world, urgent).whenComplete((snap, ex) -> {
                if (ex != null) {
                    run.fail(ex);
                    return;
                }
                try {
                    plugin.scheduling().async(() -> run.begin(snap));
                } catch (Throwable t) {
                    run.fail(t);
                }
            });
            return run.future;
        } catch (Throwable t) {
            logError("search setup", t);
            return CompletableFuture.completedFuture(
                    SearchResult.failed(SearchResult.Failure.ERROR, 0, System.nanoTime() - start));
        }
    }

    @Override
    public boolean isStillSafe(Location location, WorldSettings settings) {
        try {
            World world = location.getWorld();
            if (world == null) {
                return false;
            }
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            SearchSettings s = settings.search();
            Rules rules = resolveRules(s, world.getEnvironment(), world.getMinHeight(), world.getMaxHeight(),
                    world.getLogicalHeight());
            BlockView view = new BukkitBlockView(world, x >> 4, z >> 4, s.unsafeFloor(), s.unsafeNearby());
            if (SafetyChecker.verify(view, x, y, z, rules) != null) {
                return false;
            }
            return failingFilter(world, x, y, z, s.avoidClaims()) == null;
        } catch (Throwable t) {
            logError("isStillSafe", t);
            return false;
        }
    }

    // ---- shared helpers ------------------------------------------------------------------------------------------

    /**
     * Maps config to block rules. AUTO → SURFACE / CAVE (nether) / END (the_end). Feet Y range is the configured
     * range intersected with the world; with no configured max, CAVE stays {@value #CAVE_ROOF_MARGIN} below the
     * logical height so the nether bedrock roof (and gaps inside it) are never used.
     */
    static Rules resolveRules(SearchSettings s, World.Environment env, int minHeight, int maxHeight,
                              int logicalHeight) {
        Mode mode = switch (s.mode()) {
            case SURFACE -> Mode.SURFACE;
            case CAVE -> Mode.CAVE;
            case END -> Mode.END;
            case AUTO -> env == World.Environment.NETHER ? Mode.CAVE
                    : env == World.Environment.THE_END ? Mode.END : Mode.SURFACE;
        };
        int worldMinFeet = minHeight + 1;
        int worldMaxFeet = maxHeight - 2; // head (feet+1) must be <= maxHeight-1
        int minFeet = s.minY() == null ? worldMinFeet : Math.max(worldMinFeet, s.minY());
        int maxFeet = s.maxY() != null ? Math.min(worldMaxFeet, s.maxY()) : worldMaxFeet;
        if (mode == Mode.CAVE || env == World.Environment.NETHER) {
            // Never on/above the bedrock roof, even with an explicit max-y or a forced SURFACE mode in the nether
            // (its heightmap top is the roof itself).
            maxFeet = Math.min(maxFeet, minHeight + logicalHeight - CAVE_ROOF_MARGIN);
        }
        return new Rules(mode, minFeet, maxFeet, s.requireSkyAccess(), s.avoidLiquids());
    }

    /** Runs every applicable filter; returns the name of the first that rejects (or throws), or null if allowed. */
    private @Nullable String failingFilter(World world, int x, int y, int z, boolean avoidClaims) {
        List<LocationFilter> filters;
        try {
            filters = plugin.hooks() == null ? List.of() : plugin.hooks().filters();
        } catch (RuntimeException e) {
            filters = List.of();
        }
        for (LocationFilter f : filters) {
            String name = "?";
            try {
                name = f.name();
                if (f.isClaimFilter() && !avoidClaims) {
                    continue;
                }
                if (!f.isAllowed(world, x, y, z)) {
                    return name;
                }
            } catch (Throwable t) {
                plugin.debug("location filter '" + name + "' threw " + t + " at " + x + "," + y + "," + z
                        + " — treating as rejected");
                return name + "(error)";
            }
        }
        return null;
    }

    private BiomeMatcher matcher(BiomeFilter filter) {
        return matchers.computeIfAbsent(filter, BiomeMatcher::of);
    }

    private CompletableFuture<WorldSnapshot> snapshot(World world, boolean urgent) {
        UUID id = world.getUID();
        long now = System.nanoTime();
        WorldSnapshot cached = snapshots.get(id);
        if (cached != null && cached.ageNanos(now) < TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_TTL_MS)) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<WorldSnapshot> refresh = refreshing.computeIfAbsent(id, k -> {
            CompletableFuture<WorldSnapshot> f = new CompletableFuture<>();
            try {
                plugin.scheduling().global(() -> {
                    try {
                        WorldSnapshot fresh = WorldSnapshot.capture(world);
                        snapshots.put(id, fresh);
                        f.complete(fresh);
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    } finally {
                        refreshing.remove(id, f);
                    }
                });
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
            return f;
        });
        if (refresh.isDone()) {
            refreshing.remove(id, refresh);
        }
        if (cached != null && urgent && cached.ageNanos(now) < TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_STALE_MS)) {
            return CompletableFuture.completedFuture(cached);
        }
        return refresh;
    }

    private void logError(String context, Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String key = t.getClass().getName() + (st.length > 0 ? "@" + st[0] : "");
        if (loggedErrors.add(key)) {
            plugin.getLogger().log(Level.SEVERE, "Location search error (" + context + ")", t);
        } else {
            plugin.debug("location search error (" + context + "): " + t);
        }
    }

    // ---- one search ----------------------------------------------------------------------------------------------

    /** State of a single search. Mutable fields are guarded by {@code this}. */
    private final class Run {
        final World world;
        final WorldSettings settings;
        final SearchOptions options;
        final long startNanos;
        final CompletableFuture<SearchResult> future = new CompletableFuture<>();
        final AtomicBoolean done = new AtomicBoolean();
        final Map<String, Integer> rejections = new TreeMap<>();

        // set in begin()
        WorldArea area;
        Rules rules;
        BiomeMatcher matcher;
        @Nullable BiomeProvider biomeProvider;
        int[] precheckYs;
        long[] players;
        int maxAttempts;
        int parallelism;
        int prefilterCap;
        boolean generate;
        /** UNGENERATED_ONLY: probe with generate=false first; an existing chunk is rejected. */
        boolean probeExisting;

        int attempts;
        int prefiltered;
        int inFlight;
        boolean exhausted;

        Run(World world, WorldSettings settings, SearchOptions options, long startNanos) {
            this.world = world;
            this.settings = settings;
            this.options = options;
            this.startNanos = startNanos;
        }

        /** Async thread. */
        void begin(WorldSnapshot snap) {
            try {
                if (done.get()) {
                    return;
                }
                area = WorldArea.resolve(settings.area(), snap.spawnX(), snap.spawnZ(),
                        snap.borderCenterX(), snap.borderCenterZ(), snap.borderHalfSize());
                SearchSettings s = settings.search();
                rules = resolveRules(s, snap.environment(), snap.minHeight(), snap.maxHeight(), snap.logicalHeight());
                if (area == null || rules.minFeetY() > rules.maxFeetY()) {
                    finish(null, SearchResult.Failure.INVALID_AREA);
                    return;
                }
                matcher = matcher(s.biomes());
                biomeProvider = matcher.isUnrestricted() ? null : precheckProvider();
                precheckYs = precheckHeights(rules, snap.seaLevel());
                players = s.minDistanceFromPlayers() > 0 ? snap.players() : new long[0];
                maxAttempts = Math.max(1, options.maxAttempts() > 0 ? options.maxAttempts() : s.maxAttempts());
                parallelism = options.priority() == ChunkLoader.Priority.URGENT
                        ? Math.min(URGENT_PARALLELISM, maxAttempts) : 1;
                prefilterCap = (int) Math.min(Integer.MAX_VALUE, (long) maxAttempts * PREFILTER_FACTOR);
                generate = s.chunkPreference() != ChunkPreference.GENERATED_ONLY;
                probeExisting = s.chunkPreference() == ChunkPreference.UNGENERATED_ONLY;
            } catch (Throwable t) {
                fail(t);
                return;
            }
            pump();
        }

        private @Nullable BiomeProvider precheckProvider() {
            if (biomePrecheckBroken.contains(world.getUID())) {
                return null;
            }
            try {
                BiomeProvider custom = world.getBiomeProvider();
                if (custom != null) {
                    return custom;
                }
                // A custom generator without a biome provider may place biomes the vanilla provider can't predict.
                return world.getGenerator() != null ? null : world.vanillaBiomeProvider();
            } catch (Throwable t) {
                biomePrecheckBroken.add(world.getUID());
                plugin.debug("biome pre-check disabled for " + world.getName() + ": " + t);
                return null;
            }
        }

        /** Launches candidates until the parallelism slots are full; finishes when nothing is left to try. */
        void pump() {
            if (done.get()) {
                return;
            }
            try {
                List<Long> batch = new ArrayList<>(parallelism);
                boolean finished;
                synchronized (this) {
                    while (!exhausted && inFlight < parallelism && attempts < maxAttempts) {
                        Long c = nextCandidate();
                        if (c == null) {
                            break;
                        }
                        attempts++;
                        inFlight++;
                        batch.add(c);
                    }
                    finished = inFlight == 0;
                }
                if (finished) {
                    finish(null, SearchResult.Failure.NO_SAFE_LOCATION);
                    return;
                }
                for (long c : batch) {
                    loadAndCheck(SearchOptions.unpackX(c), SearchOptions.unpackZ(c), probeExisting);
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        /** Samples until a column passes every pre-filter. Guarded by {@code this}. */
        private @Nullable Long nextCandidate() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (prefiltered < prefilterCap) {
                long packed = AreaSampler.sample(rnd, area.shape(), area.centerX(), area.centerZ(),
                        area.minRadius(), area.maxRadius());
                int x = SearchOptions.unpackX(packed);
                int z = SearchOptions.unpackZ(packed);
                String why = prefilter(x, z);
                if (why == null) {
                    return packed;
                }
                prefiltered++;
                reject(why);
            }
            exhausted = true;
            return null;
        }

        private @Nullable String prefilter(int x, int z) {
            if (!area.insideBorder(x, z)) {
                return "border";
            }
            long[] avoid = options.avoidNear();
            if (avoid != null && options.avoidRadius() > 0 && within(avoid, x, z, options.avoidRadius())) {
                return "spacing";
            }
            int playerDist = settings.search().minDistanceFromPlayers();
            if (playerDist > 0 && within(players, x, z, playerDist)) {
                return "near-player";
            }
            if (biomeProvider != null && !precheckBiome(x, z)) {
                return "biome-estimate";
            }
            // Chunk preference is NOT pre-checked with World#isChunkGenerated: off the main thread Paper hops to the
            // main thread and blocks it on a chunk-status disk read for every call. GENERATED_ONLY loads with
            // generate=false instead (missing chunk = cheap async rejection); UNGENERATED_ONLY probes the same way
            // before generating (see loadAndCheck).
            return null;
        }

        private boolean precheckBiome(int x, int z) {
            BiomeProvider provider = biomeProvider;
            if (provider == null) {
                return true;
            }
            try {
                for (int y : precheckYs) {
                    Biome b = provider.getBiome(world, x, y, z);
                    if (b != null && matcher.allows(b.key().asString())) {
                        return true;
                    }
                }
                return false;
            } catch (Throwable t) {
                biomeProvider = null;
                biomePrecheckBroken.add(world.getUID());
                plugin.debug("biome pre-check disabled for " + world.getName() + ": " + t);
                return true;
            }
        }

        /**
         * Loads the candidate's chunk and evaluates it on the owning thread. With {@code probe} the chunk is first
         * loaded without generation: if it exists the candidate is rejected ("already-generated"), otherwise it is
         * loaded again with generation and evaluated.
         */
        private void loadAndCheck(int x, int z, boolean probe) {
            int cx = x >> 4;
            int cz = z >> 4;
            CompletableFuture<Chunk> load;
            try {
                load = plugin.chunkLoader().load(world, cx, cz, !probe && generate, options.priority());
            } catch (Throwable t) {
                load = CompletableFuture.failedFuture(t);
            }
            if (probe) {
                load.whenComplete((chunk, ex) -> onProbe(x, z, ex));
                return;
            }
            load.whenComplete((chunk, ex) -> {
                if (ex == null && !Bukkit.isOwnedByCurrentRegion(world, cx, cz)) {
                    // Defensive: the loader promises owning-thread completion, but never touch blocks off-thread.
                    try {
                        plugin.scheduling().region(world, cx, cz, () -> onChunk(x, z, chunk, null));
                    } catch (Throwable t) {
                        onChunk(x, z, null, t);
                    }
                    return;
                }
                onChunk(x, z, chunk, ex);
            });
        }

        /** UNGENERATED_ONLY probe result. Any thread. */
        private void onProbe(int x, int z, @Nullable Throwable ex) {
            if (done.get()) {
                return;
            }
            if (ex == null) {
                rejectAndContinue("already-generated");
                return;
            }
            if (DefaultChunkLoader.unwrap(ex) instanceof ChunkLoader.ChunkNotGeneratedException) {
                loadAndCheck(x, z, false);
                return;
            }
            onChunk(x, z, null, ex);
        }

        /** Owning region thread (or any thread when {@code ex != null}). */
        private void onChunk(int x, int z, @Nullable Chunk chunk, @Nullable Throwable ex) {
            if (done.get()) {
                return;
            }
            String reason;
            try {
                if (ex != null) {
                    Throwable cause = DefaultChunkLoader.unwrap(ex);
                    if (cause instanceof CancellationException) {
                        finish(null, SearchResult.Failure.CANCELLED);
                        return;
                    }
                    if (cause instanceof ChunkLoader.ChunkNotGeneratedException) {
                        reason = "ungenerated";
                    } else {
                        reason = "load-error";
                        plugin.debug("chunk load failed at " + (x >> 4) + "," + (z >> 4) + " in " + world.getName()
                                + ": " + cause);
                    }
                } else {
                    Eval result = evaluate(x, z);
                    if (result.location() != null) {
                        finish(result.location(), null);
                        return;
                    }
                    reason = result.reason();
                }
            } catch (Throwable t) {
                fail(t);
                return;
            }
            rejectAndContinue(reason);
        }

        /** Ends one in-flight candidate as rejected and launches the next. */
        private void rejectAndContinue(String reason) {
            synchronized (this) {
                inFlight--;
                reject(reason);
            }
            try {
                plugin.scheduling().async(this::pump);
            } catch (Throwable t) {
                // Scheduler unavailable (plugin disabling).
                finish(null, SearchResult.Failure.CANCELLED);
            }
        }

        /** Owning region thread, chunk loaded. */
        private Eval evaluate(int x, int z) {
            SearchSettings s = settings.search();
            BlockView view = new BukkitBlockView(world, x >> 4, z >> 4, s.unsafeFloor(), s.unsafeNearby());
            int caveStart = 0;
            if (rules.mode() == Mode.CAVE) {
                int lo = SafetyChecker.caveBottom(view, rules);
                int hi = SafetyChecker.caveTop(view, rules);
                caveStart = hi > lo ? ThreadLocalRandom.current().nextInt(lo, hi + 1) : hi;
            }
            SafetyChecker.Result r = SafetyChecker.find(view, x, z, rules, caveStart);
            if (!r.safe()) {
                return Eval.rejected(r.reason().key);
            }
            int feetY = r.feetY();
            if (!matcher.isUnrestricted()) {
                Biome biome = world.getBiome(x, feetY, z);
                if (biome == null || !matcher.allows(biome.key().asString())) {
                    return Eval.rejected("biome");
                }
            }
            String filter = failingFilter(world, x, feetY, z, s.avoidClaims());
            if (filter != null) {
                return Eval.rejected("filter:" + filter);
            }
            float yaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
            return new Eval(new Location(world, x + 0.5, feetY, z + 0.5, yaw, 0f), "");
        }

        private void reject(String reason) {
            rejections.merge(reason, 1, Integer::sum);
        }

        void fail(Throwable t) {
            Throwable cause = DefaultChunkLoader.unwrap(t);
            if (cause instanceof CancellationException) {
                finish(null, SearchResult.Failure.CANCELLED);
                return;
            }
            logError("search in " + world.getName(), cause);
            finish(null, SearchResult.Failure.ERROR);
        }

        /** Completes the search exactly once. */
        void finish(@Nullable Location location, SearchResult.@Nullable Failure failure) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            active.remove(this);
            long elapsed = System.nanoTime() - startNanos;
            int tried;
            String summary = null;
            synchronized (this) {
                tried = attempts;
                if (debugEnabled()) {
                    summary = summary(location, failure, elapsed);
                }
            }
            SearchResult result = location != null
                    ? SearchResult.found(location, tried, elapsed)
                    : SearchResult.failed(failure == null ? SearchResult.Failure.ERROR : failure, tried, elapsed);
            if (summary != null) {
                plugin.debug(summary);
            }
            future.complete(result);
        }

        private boolean debugEnabled() {
            PluginSettings ps = plugin.settings();
            return ps != null && ps.debug();
        }

        private String summary(@Nullable Location location, SearchResult.@Nullable Failure failure, long elapsed) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("search ").append(world.getName()).append(' ').append(options.priority())
                    .append(" -> ").append(location != null ? "FOUND" : String.valueOf(failure));
            if (location != null) {
                sb.append(" @").append(location.getBlockX()).append(',').append(location.getBlockY()).append(',')
                        .append(location.getBlockZ());
            }
            sb.append(" attempts=").append(attempts).append('/').append(maxAttempts)
                    .append(" prefiltered=").append(prefiltered)
                    .append(String.format(" time=%.1fms", elapsed / 1_000_000.0));
            if (!rejections.isEmpty()) {
                sb.append(" rejects:");
                rejections.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            }
            return sb.toString();
        }
    }

    /** Outcome of evaluating one loaded column: a location, or the rejection reason key. */
    private record Eval(@Nullable Location location, String reason) {
        static Eval rejected(String reason) {
            return new Eval(null, reason);
        }
    }

    /** Heights at which the biome provider is sampled for the pre-check (any allowed biome passes). */
    static int[] precheckHeights(Rules rules, int seaLevel) {
        int lo = rules.minFeetY();
        int hi = rules.maxFeetY();
        if (rules.mode() == Mode.CAVE) {
            int mid = lo + (hi - lo) / 2;
            return new int[] {lo + (hi - lo) / 4, mid, hi - (hi - lo) / 4};
        }
        // Surface biomes depend on depth below the terrain surface; sampling at sea level and well above it keeps
        // mountain biomes (peaks, slopes) from being mispredicted as underground/lowland ones.
        return new int[] {Math.clamp(seaLevel, lo, hi), Math.clamp(seaLevel + 48L, lo, hi)};
    }

    /** True if (x, z) is within {@code radius} blocks (euclidean, X/Z) of any packed point. */
    static boolean within(long[] points, int x, int z, int radius) {
        long r2 = (long) radius * radius;
        for (long p : points) {
            long dx = (long) SearchOptions.unpackX(p) - x;
            long dz = (long) SearchOptions.unpackZ(p) - z;
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }
}

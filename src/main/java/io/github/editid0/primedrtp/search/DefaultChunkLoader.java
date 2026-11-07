package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.config.PluginSettings;
import io.github.editid0.primedrtp.config.PluginSettings.PerformanceSettings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Throttled async chunk loader.
 * <ul>
 *   <li>URGENT requests are dispatched immediately with Paper's urgent priority
 *       ({@code getChunkAtAsync(x, z, generate, true)}); they bypass every throttle but are counted in stats.</li>
 *   <li>BACKGROUND requests go into a FIFO queue drained once per tick by a global-region timer, honouring
 *       {@code performance.max-concurrent-chunk-loads} (in-flight cap, {@code <= 0} = unlimited),
 *       {@code max-chunk-loads-per-second} (token bucket, {@code <= 0} = unlimited), {@code pause-above-mspt},
 *       {@code pause-when-empty} and {@code startup-delay}.</li>
 * </ul>
 * Every returned future completes on the thread owning the chunk. Settings are re-read every tick, so reloads apply
 * immediately.
 */
public final class DefaultChunkLoader implements ChunkLoader {

    /** Hard per-tick dispatch cap when both throttles are unlimited, to avoid flooding the chunk system in one tick. */
    private static final int MAX_DISPATCH_PER_TICK = 256;

    private final PrimedRTP plugin;
    private final ConcurrentLinkedDeque<Request> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final AtomicInteger backgroundInFlight = new AtomicInteger();
    private final AtomicInteger urgentInFlight = new AtomicInteger();
    private final AtomicLong loaded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final TokenBucket bucket = new TokenBucket();

    private volatile boolean stopped;
    private volatile long startedAtNanos = System.nanoTime();
    private volatile @Nullable String pauseReason = "starting";
    private volatile boolean msptSupported = true;
    private volatile @Nullable ScheduledTask timer;
    private volatile boolean tickErrorLogged;

    private record Request(World world, int x, int z, boolean generate, CompletableFuture<Chunk> future) {}

    public DefaultChunkLoader(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void start() {
        stopped = false;
        startedAtNanos = System.nanoTime();
        timer = plugin.scheduling().globalTimer(t -> tick(), 1L, 1L);
    }

    @Override
    public void stop() {
        stopped = true;
        ScheduledTask t = timer;
        timer = null;
        if (t != null) {
            try {
                t.cancel();
            } catch (RuntimeException ignored) {
                // scheduler already shut down
            }
        }
        cancelQueued();
        pauseReason = "stopped";
    }

    private void cancelQueued() {
        Request r;
        while ((r = queue.pollFirst()) != null) {
            queuedCount.decrementAndGet();
            r.future().completeExceptionally(new CancellationException("chunk loader stopped"));
        }
    }

    // ---- API -----------------------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Chunk> load(World world, int chunkX, int chunkZ, boolean generate, Priority priority) {
        CompletableFuture<Chunk> future = new CompletableFuture<>();
        if (stopped) {
            future.completeExceptionally(new CancellationException("chunk loader stopped"));
            return future;
        }
        Request req = new Request(world, chunkX, chunkZ, generate, future);
        if (priority == Priority.URGENT) {
            dispatch(req, true);
        } else {
            queue.offerLast(req);
            queuedCount.incrementAndGet();
            if (stopped && queue.remove(req)) {
                // raced with stop()
                queuedCount.decrementAndGet();
                future.completeExceptionally(new CancellationException("chunk loader stopped"));
            }
        }
        return future;
    }

    @Override
    public boolean isPaused() {
        return pauseReason != null;
    }

    @Override
    public @Nullable String pauseReason() {
        return pauseReason;
    }

    @Override
    public int inFlight() {
        return backgroundInFlight.get() + urgentInFlight.get();
    }

    @Override
    public int queued() {
        return Math.max(0, queuedCount.get());
    }

    @Override
    public long totalLoaded() {
        return loaded.get();
    }

    /** Loads that completed with an error or a missing chunk (diagnostics). */
    public long totalFailed() {
        return failed.get();
    }

    // ---- background drain ----------------------------------------------------------------------------------------

    /** Runs once per tick on the global region thread. */
    private void tick() {
        if (stopped) {
            return;
        }
        try {
            PluginSettings settings = plugin.settings();
            if (settings == null) {
                pauseReason = "no configuration";
                return;
            }
            PerformanceSettings perf = settings.performance();
            String reason = computePauseReason(perf);
            pauseReason = reason;
            if (reason != null) {
                return;
            }
            int cap = perf.maxConcurrentChunkLoads();
            double rate = perf.maxChunkLoadsPerSecond();
            double capacity = TokenBucket.defaultCapacity(rate);
            int dispatched = 0;
            while (dispatched < MAX_DISPATCH_PER_TICK && !stopped) {
                if (cap > 0 && backgroundInFlight.get() >= cap) {
                    break;
                }
                Request next = queue.pollFirst();
                if (next == null) {
                    break;
                }
                if (next.future().isDone()) {
                    // Caller gave up (cancelled) while queued: drop without spending a token.
                    queuedCount.decrementAndGet();
                    continue;
                }
                if (!bucket.tryAcquire(System.nanoTime(), rate, capacity)) {
                    // This tick is the only consumer, so putting it back at the head keeps FIFO order.
                    queue.offerFirst(next);
                    break;
                }
                queuedCount.decrementAndGet();
                dispatch(next, false);
                dispatched++;
            }
            if (stopped) {
                cancelQueued(); // a request may have been pushed back after stop() drained the queue
            }
        } catch (Throwable t) {
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                plugin.getLogger().log(Level.SEVERE, "Chunk loader tick failed", t);
            } else {
                plugin.debug("chunk loader tick failed: " + t);
            }
        }
    }

    private @Nullable String computePauseReason(PerformanceSettings perf) {
        long sinceStart = System.nanoTime() - startedAtNanos;
        long delay = perf.startupDelay() == null ? 0 : perf.startupDelay().toNanos();
        if (sinceStart < delay) {
            long left = (delay - sinceStart + 999_999_999L) / 1_000_000_000L;
            return "startup delay (" + left + "s left)";
        }
        if (perf.pauseWhenEmpty() && Bukkit.getOnlinePlayers().isEmpty()) {
            return "no players online";
        }
        if (perf.pauseAboveMspt() > 0 && msptSupported) {
            try {
                double mspt = Bukkit.getAverageTickTime();
                if (mspt > perf.pauseAboveMspt()) {
                    return String.format("server lagging (%.1f mspt > %.1f)", mspt, perf.pauseAboveMspt());
                }
            } catch (UnsupportedOperationException | NoSuchMethodError e) {
                msptSupported = false;
                plugin.getLogger().info("Average MSPT is not available on this server (Folia?); "
                        + "performance.pause-above-mspt is disabled.");
            }
        }
        return null;
    }

    // ---- dispatch ------------------------------------------------------------------------------------------------

    private void dispatch(Request req, boolean urgent) {
        AtomicInteger counter = urgent ? urgentInFlight : backgroundInFlight;
        counter.incrementAndGet();
        CompletableFuture<Chunk> paper;
        try {
            if (Bukkit.getWorld(req.world().getUID()) == null) {
                throw new IllegalStateException("world " + req.world().getName() + " is not loaded");
            }
            paper = req.world().getChunkAtAsync(req.x(), req.z(), req.generate(), urgent);
        } catch (Throwable t) {
            counter.decrementAndGet();
            failed.incrementAndGet();
            req.future().completeExceptionally(t);
            return;
        }
        paper.whenComplete((chunk, ex) -> {
            counter.decrementAndGet();
            onLoaded(req, chunk, ex);
        });
    }

    /** Runs on the thread completing Paper's future (normally the chunk-owning thread). */
    private void onLoaded(Request req, @Nullable Chunk chunk, @Nullable Throwable ex) {
        CompletableFuture<Chunk> out = req.future();
        if (ex != null) {
            failed.incrementAndGet();
            out.completeExceptionally(unwrap(ex));
            return;
        }
        if (chunk == null) {
            failed.incrementAndGet();
            out.completeExceptionally(new ChunkNotGeneratedException(req.x(), req.z()));
            return;
        }
        if (stopped) {
            out.completeExceptionally(new CancellationException("chunk loader stopped"));
            return;
        }
        loaded.incrementAndGet();
        try {
            if (Bukkit.isOwnedByCurrentRegion(req.world(), req.x(), req.z())) {
                out.complete(chunk);
            } else {
                // Defensive: guarantee dependents run on the owning thread.
                plugin.scheduling().region(req.world(), req.x(), req.z(), () -> out.complete(chunk));
            }
        } catch (Throwable t) {
            out.completeExceptionally(t);
        }
    }

    static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}

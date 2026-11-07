package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.api.event.RtpPostTeleportEvent;
import io.github.editid0.swiftrtp.api.event.RtpPreTeleportEvent;
import io.github.editid0.swiftrtp.api.event.RtpTeleportEvent;
import io.github.editid0.swiftrtp.config.MessageService;
import io.github.editid0.swiftrtp.config.PluginSettings;
import io.github.editid0.swiftrtp.config.WorldSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.EffectSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.TeleportSettings;
import io.github.editid0.swiftrtp.pool.PooledLocation;
import io.github.editid0.swiftrtp.search.SearchOptions;
import io.github.editid0.swiftrtp.search.SearchResult;
import io.github.editid0.swiftrtp.teleport.TeleportOutcome.Status;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Default {@link TeleportService}. Each request becomes a {@link Pending} state machine
 * ({@code WARMUP → RESOLVING → TELEPORTING → DONE}) driven on the player's thread:
 * <ol>
 *   <li>checks (world, permission, pending, cooldown, cost) and {@link RtpPreTeleportEvent};</li>
 *   <li>the destination is reserved when the warmup STARTS — a pooled entry is taken immediately, or an URGENT live
 *       search runs in parallel with the countdown — so arrival is instant when the countdown ends;</li>
 *   <li>warmup countdown (entity timer, every tick): move/damage/quit/death/command cancel it;</li>
 *   <li>charge, re-validate the pooled entry on its region thread (retrying up to {@code max-retries}),
 *       {@link RtpTeleportEvent}, {@code teleportAsync};</li>
 *   <li>protections, cooldown, messages, effects, commands, {@link RtpPostTeleportEvent}.</li>
 * </ol>
 * Every exit path goes through {@link Pending#finish}, which runs exactly once: it releases/restores the pooled
 * entry, refunds if needed, removes boss bars, notifies the initiator and completes the future.
 */
public final class DefaultTeleportService implements TeleportService {

    static final String PERM_BYPASS_COOLDOWN = "swiftrtp.bypass.cooldown";
    static final String PERM_BYPASS_COST = "swiftrtp.bypass.cost";
    static final String PERM_BYPASS_WARMUP = "swiftrtp.bypass.warmup";

    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500));

    private enum Phase { WARMUP, RESOLVING, TELEPORTING, DONE }

    private final SwiftRTP plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final CostHandler costs;
    private final EffectPlayer effects;
    private final ProtectionListener protection = new ProtectionListener();
    private final WarmupListener warmupListener = new WarmupListener(this);
    private volatile boolean running;

    public DefaultTeleportService(SwiftRTP plugin) {
        this.plugin = plugin;
        this.costs = new CostHandler(plugin);
        this.effects = new EffectPlayer(plugin);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public void start() {
        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(protection, plugin);
        pm.registerEvents(warmupListener, plugin);
        running = true;
    }

    @Override
    public void reload() {
        effects.clearCache();
    }

    @Override
    public void stop() {
        running = false;
        for (Pending p : new ArrayList<>(pending.values())) {
            p.abortForShutdown();
        }
        pending.clear();
        HandlerList.unregisterAll(protection);
        HandlerList.unregisterAll(warmupListener);
        protection.clear();
    }

    // ---- public API ------------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<TeleportOutcome> teleport(TeleportRequest request) {
        CompletableFuture<TeleportOutcome> future = new CompletableFuture<>();
        if (!running) {
            future.complete(TeleportOutcome.of(Status.TELEPORT_FAILED));
            return future;
        }
        Player player = request.player();
        try {
            onPlayer(player, () -> {
                try {
                    begin(request, future);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Unexpected error starting RTP for " + player.getName(), t);
                    future.complete(TeleportOutcome.of(Status.TELEPORT_FAILED));
                }
            }, () -> future.complete(TeleportOutcome.of(Status.CANCELLED_OTHER)));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Could not schedule RTP for " + player.getName(), t);
            future.complete(TeleportOutcome.of(Status.TELEPORT_FAILED));
        }
        return future;
    }

    @Override
    public Optional<World> resolveTargetWorld(Player player, @Nullable World requested) {
        if (requested != null) {
            return Optional.of(requested);
        }
        PluginSettings s = plugin.settings();
        return WorldResolution.resolve(player.getWorld().getName(), s.worldRedirects(),
                        name -> s.world(name).map(WorldSettings::enabled).orElse(false),
                        s.defaultWorld(), name -> Bukkit.getWorld(name) != null)
                .map(Bukkit::getWorld);
    }

    @Override
    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    @Override
    public boolean cancel(Player player) {
        Pending p = pending.get(player.getUniqueId());
        return p != null && p.tryCancel(Status.CANCELLED_OTHER, "teleport.cancelled");
    }

    // ---- listener callbacks (player's thread) ----------------------------------------------------------------

    void onWarmupDamage(Player player) {
        Pending p = pending.get(player.getUniqueId());
        if (p != null && p.phase.get() == Phase.WARMUP && p.ws.teleport().cancelOnDamage()) {
            p.tryCancel(Status.CANCELLED_DAMAGE, "teleport.cancelled-damage");
        }
    }

    void cancelSilently(Player player) {
        Pending p = pending.get(player.getUniqueId());
        if (p != null) {
            p.tryCancel(Status.CANCELLED_OTHER, null);
        }
    }

    // ---- request start (player's thread) ---------------------------------------------------------------------

    private void begin(TeleportRequest request, CompletableFuture<TeleportOutcome> future) {
        Player player = request.player();
        MessageService msg = plugin.messages();
        PluginSettings settings = plugin.settings();

        Optional<World> resolved = resolveTargetWorld(player, request.targetWorld());
        if (resolved.isEmpty()) {
            reject(request, future, TeleportOutcome.of(Status.WORLD_NOT_FOUND), "general.no-world-available");
            return;
        }
        World world = resolved.get();
        if (Bukkit.getWorld(world.getUID()) == null) {
            reject(request, future, TeleportOutcome.of(Status.WORLD_NOT_FOUND), "general.world-not-found",
                    Placeholder.unparsed("world", world.getName()));
            return;
        }
        Optional<WorldSettings> wsOpt = settings.world(world.getName());
        if (wsOpt.isEmpty() || !wsOpt.get().enabled()) {
            reject(request, future, TeleportOutcome.of(Status.WORLD_DISABLED), "general.world-disabled",
                    Placeholder.unparsed("world", world.getName()));
            return;
        }
        WorldSettings ws = wsOpt.get();

        if (request.checkPermission() && !io.github.editid0.swiftrtp.Permissions.canUseWorld(player, ws)) {
            reject(request, future, TeleportOutcome.of(Status.NO_PERMISSION), "general.no-permission");
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            reject(request, future, TeleportOutcome.of(Status.ALREADY_PENDING), "teleport.already-pending");
            return;
        }

        boolean bypassCooldown = request.bypassCooldown() || player.hasPermission(PERM_BYPASS_COOLDOWN);
        boolean bypassCost = request.bypassCost() || player.hasPermission(PERM_BYPASS_COST);
        boolean bypassWarmup = request.bypassWarmup() || player.hasPermission(PERM_BYPASS_WARMUP);

        if (!bypassCooldown) {
            Duration remaining = plugin.cooldowns().remaining(player, ws);
            if (remaining.isPositive()) {
                reject(request, future, new TeleportOutcome(Status.ON_COOLDOWN, null, remaining), "teleport.cooldown",
                        Placeholder.unparsed("time", msg.formatDuration(remaining)),
                        Placeholder.unparsed("world", world.getName()));
                return;
            }
        }
        CostHandler.Receipt cost = bypassCost ? CostHandler.Receipt.EMPTY : costs.effectiveCost(ws.cost());
        if (!cost.isEmpty() && !costs.canAfford(player, cost)) {
            reject(request, future, TeleportOutcome.of(Status.INSUFFICIENT_FUNDS), "teleport.insufficient-funds",
                    Placeholder.component("cost", costs.describe(cost)));
            return;
        }

        RtpPreTeleportEvent pre = new RtpPreTeleportEvent(player, world, request.cause());
        if (!pre.callEvent()) {
            plugin.debug("RtpPreTeleportEvent cancelled for " + player.getName());
            reject(request, future, TeleportOutcome.of(Status.CANCELLED_EVENT), null);
            return;
        }
        if (!pre.getTargetWorld().getUID().equals(world.getUID())) {
            world = pre.getTargetWorld();
            Optional<WorldSettings> changed = plugin.settings().world(world.getName());
            if (changed.isEmpty() || !changed.get().enabled()) {
                reject(request, future, TeleportOutcome.of(Status.WORLD_DISABLED), "general.world-disabled",
                        Placeholder.unparsed("world", world.getName()));
                return;
            }
            ws = changed.get();
            cost = bypassCost ? CostHandler.Receipt.EMPTY : costs.effectiveCost(ws.cost());
        }

        Pending p = new Pending(request, future, world, ws, cost, bypassCooldown, bypassWarmup);
        if (pending.putIfAbsent(player.getUniqueId(), p) != null) {
            reject(request, future, TeleportOutcome.of(Status.ALREADY_PENDING), "teleport.already-pending");
            return;
        }
        if (p.initiatorIsOther()) {
            msg.send(p.initiator(), "teleport.sent-other", Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("world", world.getName()));
        }
        p.guard(p::startWarmup);
    }

    /**
     * Ends a request before a {@link Pending} exists. Self requests show {@code key} to the player; requests by
     * another sender report {@code teleport.sent-other-failed} to that sender instead.
     */
    private void reject(TeleportRequest request, CompletableFuture<TeleportOutcome> future, TeleportOutcome outcome,
                        @Nullable String key, TagResolver... placeholders) {
        Player player = request.player();
        CommandSender initiator = request.initiator();
        if (isOther(initiator, player)) {
            plugin.messages().send(initiator, "teleport.sent-other-failed",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("world", request.targetWorld() == null ? "" : request.targetWorld().getName()),
                    Placeholder.component("reason", reason(outcome.status())));
        } else if (key != null) {
            plugin.messages().send(player, key, placeholders);
        }
        future.complete(outcome);
    }

    private Component reason(Status status) {
        return plugin.messages().get(reasonKey(status));
    }

    /** {@code teleport.reason.<status-in-kebab-case>}, e.g. {@code teleport.reason.no-location-found}. */
    static String reasonKey(Status status) {
        return "teleport.reason." + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean isOther(@Nullable CommandSender initiator, Player player) {
        if (initiator == null || initiator == player) {
            return false;
        }
        return !(initiator instanceof Player p && p.getUniqueId().equals(player.getUniqueId()));
    }

    /** Runs on the player's thread (inline if already there); {@code retired} runs if the player is gone. */
    private void onPlayer(Player player, Runnable task, Runnable retired) {
        if (plugin.scheduling().owns(player)) {
            task.run();
        } else if (!plugin.scheduling().entity(player, task, retired)) {
            retired.run();
        }
    }

    // ==========================================================================================================
    // Pending teleport state machine
    // ==========================================================================================================

    private final class Pending {
        final TeleportRequest request;
        final Player player;
        final UUID uuid;
        final CompletableFuture<TeleportOutcome> future;
        final World world;
        final WorldSettings ws;
        final CostHandler.Receipt cost;
        final boolean bypassCooldown;
        final boolean bypassWarmup;

        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.WARMUP);
        final AtomicBoolean finished = new AtomicBoolean();
        /** Pooled entry currently owned by this request (null if none). */
        final AtomicReference<@Nullable PooledLocation> entry = new AtomicReference<>();

        volatile @Nullable CompletableFuture<SearchResult> liveSearch;
        volatile @Nullable ScheduledTask timer;
        volatile @Nullable BossBar bossBar;
        volatile CostHandler.@Nullable Receipt charged;
        volatile boolean searchingSent;
        /** Set once teleportAsync was invoked (entry is then released, never restored). */
        volatile boolean teleportAttempted;
        volatile long warmupEndNanos;

        // warmup state (player's thread only)
        Location startLocation;
        long totalTicks;
        long elapsedTicks;

        Pending(TeleportRequest request, CompletableFuture<TeleportOutcome> future, World world, WorldSettings ws,
                CostHandler.Receipt cost, boolean bypassCooldown, boolean bypassWarmup) {
            this.request = request;
            this.player = request.player();
            this.uuid = player.getUniqueId();
            this.future = future;
            this.world = world;
            this.ws = ws;
            this.cost = cost;
            this.bypassCooldown = bypassCooldown;
            this.bypassWarmup = bypassWarmup;
        }

        CommandSender initiator() {
            return request.initiator();
        }

        boolean initiatorIsOther() {
            return isOther(request.initiator(), player);
        }

        TagResolver worldPlaceholder() {
            return Placeholder.unparsed("world", world.getName());
        }

        // ---- warmup ------------------------------------------------------------------------------------------

        void startWarmup() {
            TeleportSettings ts = ws.teleport();
            startLocation = player.getLocation();

            // Reserve the destination now so it is ready the instant the countdown ends.
            Optional<PooledLocation> taken = plugin.pools().take(world);
            if (taken.isPresent()) {
                PooledLocation reserved = taken.get();
                entry.set(reserved);
                if (finished.get()) {
                    // finish() ran concurrently (e.g. shutdown on another thread) before it could see this entry.
                    if (entry.compareAndSet(reserved, null)) {
                        plugin.pools().restore(reserved);
                    }
                    return;
                }
                plugin.debug("RTP " + player.getName() + ": reserved pooled location #" + reserved.id());
            } else {
                plugin.debug("RTP " + player.getName() + ": pool empty for " + world.getName() + ", live search");
                liveSearch = plugin.searcher().search(world, ws, SearchOptions.urgent());
            }

            totalTicks = bypassWarmup ? 0 : MovementCheck.toTicks(ts.warmup().toMillis());
            if (totalTicks <= 0) {
                endWarmup();
                return;
            }

            int seconds = MovementCheck.displaySeconds(totalTicks);
            TagResolver[] ph = {Placeholder.unparsed("seconds", Integer.toString(seconds)), worldPlaceholder()};
            plugin.messages().send(player, "teleport.warmup", ph);
            EffectSettings fx = ts.effects();
            if (fx.bossBarCountdown()) {
                BossBar bar = BossBar.bossBar(plugin.messages().get("teleport.warmup-bossbar", ph), 1f,
                        BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                bossBar = bar;
                player.showBossBar(bar);
                if (finished.get()) {
                    // finish() may have run before the bar was published and so could not hide it.
                    hideBossBar();
                    return;
                }
            }
            secondTick(seconds);

            ScheduledTask task = plugin.scheduling().entityTimer(player, t -> guard(() -> tick(t)),
                    () -> tryCancel(Status.CANCELLED_OTHER, null), 1L, 1L);
            if (task == null) {
                tryCancel(Status.CANCELLED_OTHER, null);
                return;
            }
            timer = task;
            if (finished.get()) {
                task.cancel();
            }
        }

        private void tick(ScheduledTask task) {
            if (finished.get() || phase.get() != Phase.WARMUP) {
                task.cancel();
                return;
            }
            TeleportSettings ts = ws.teleport();
            if (ts.cancelOnMove() && moved(ts.moveTolerance())) {
                task.cancel();
                tryCancel(Status.CANCELLED_MOVE, "teleport.cancelled-move");
                return;
            }
            elapsedTicks++;
            long remaining = totalTicks - elapsedTicks;
            if (remaining <= 0) {
                task.cancel();
                endWarmup();
                return;
            }
            BossBar bar = bossBar;
            if (bar != null) {
                bar.progress(Math.max(0f, Math.min(1f, (float) remaining / (float) totalTicks)));
            }
            if (remaining % 20 == 0) {
                secondTick(MovementCheck.displaySeconds(remaining));
            }
        }

        private boolean moved(double tolerance) {
            Location now = player.getLocation();
            if (now.getWorld() == null || startLocation.getWorld() == null
                    || !now.getWorld().getUID().equals(startLocation.getWorld().getUID())) {
                return true;
            }
            return MovementCheck.exceeds(startLocation.getX(), startLocation.getY(), startLocation.getZ(),
                    now.getX(), now.getY(), now.getZ(), tolerance);
        }

        /** Once-per-second countdown feedback. */
        private void secondTick(int seconds) {
            EffectSettings fx = ws.teleport().effects();
            TagResolver[] ph = {Placeholder.unparsed("seconds", Integer.toString(seconds)), worldPlaceholder()};
            if (fx.actionBarCountdown()) {
                plugin.messages().actionBar(player, "teleport.warmup-actionbar", ph);
            }
            BossBar bar = bossBar;
            if (bar != null) {
                bar.name(plugin.messages().get("teleport.warmup-bossbar", ph));
            }
            effects.playSound(player, fx.warmupTickSound());
            effects.spawnParticles(player, fx.warmupParticles());
        }

        /** Countdown over: charge and find the destination. Player's thread. */
        private void endWarmup() {
            if (!phase.compareAndSet(Phase.WARMUP, Phase.RESOLVING)) {
                return;
            }
            warmupEndNanos = System.nanoTime();
            hideBossBar();

            if (!cost.isEmpty()) {
                CostHandler.Receipt receipt = costs.charge(player, cost);
                if (receipt == null) {
                    plugin.messages().send(player, "teleport.insufficient-funds",
                            Placeholder.component("cost", costs.describe(cost)));
                    finish(TeleportOutcome.of(Status.INSUFFICIENT_FUNDS), false);
                    return;
                }
                charged = receipt;
            }

            int retries = Math.max(0, ws.teleport().maxRetries());
            PooledLocation e = entry.get();
            CompletableFuture<SearchResult> live = liveSearch;
            if (e != null) {
                validatePooled(e, retries);
            } else if (live != null) {
                awaitLive(live);
            } else {
                startLiveSearch();
            }
        }

        // ---- destination ---------------------------------------------------------------------------------

        /** Re-checks a pooled entry on its region thread, then continues on the player's thread. */
        private void validatePooled(PooledLocation e, int retriesLeft) {
            World w = Bukkit.getWorld(e.worldId());
            if (w == null) {
                discardAndRetry(e, retriesLeft);
                return;
            }
            Location loc = e.toLocation(w);
            plugin.scheduling().regionOrNow(w, e.chunkX(), e.chunkZ(), () -> guard(() -> {
                if (finished.get()) {
                    return;
                }
                boolean safe = plugin.searcher().isStillSafe(loc, ws);
                onPlayer(player, () -> guard(() -> {
                    if (safe) {
                        arrive(loc, true);
                    } else {
                        plugin.debug("Pooled location #" + e.id() + " became unsafe; retrying (" + retriesLeft + " left)");
                        discardAndRetry(e, retriesLeft);
                    }
                }), () -> tryCancel(Status.CANCELLED_OTHER, null));
            }));
        }

        /** Releases an unusable entry and tries the next pooled one, then a live search. Player's thread. */
        private void discardAndRetry(PooledLocation bad, int retriesLeft) {
            if (entry.compareAndSet(bad, null)) {
                plugin.pools().release(bad);
            }
            if (finished.get()) {
                return;
            }
            if (retriesLeft <= 0) {
                noLocation();
                return;
            }
            Optional<PooledLocation> next = plugin.pools().take(world);
            if (next.isPresent()) {
                PooledLocation n = next.get();
                entry.set(n);
                if (finished.get()) {
                    // finish() ran concurrently and could not see this entry: give it back ourselves.
                    if (entry.compareAndSet(n, null)) {
                        plugin.pools().restore(n);
                    }
                    return;
                }
                validatePooled(n, retriesLeft - 1);
            } else {
                startLiveSearch();
            }
        }

        private void startLiveSearch() {
            CompletableFuture<SearchResult> live = plugin.searcher().search(world, ws, SearchOptions.urgent());
            liveSearch = live;
            awaitLive(live);
        }

        private void awaitLive(CompletableFuture<SearchResult> live) {
            if (!live.isDone() && !searchingSent) {
                searchingSent = true;
                plugin.messages().send(player, "teleport.searching", worldPlaceholder());
            }
            live.whenComplete((result, error) -> onPlayer(player, () -> guard(() -> {
                if (finished.get()) {
                    return;
                }
                if (error != null) {
                    throw new IllegalStateException("Live search failed", error);
                }
                if (result != null && result.success() && result.location() != null) {
                    plugin.debug("Live search for " + player.getName() + " succeeded after " + result.attempts()
                            + " attempt(s)");
                    arrive(result.location(), false);
                } else if (result != null && result.failure() == SearchResult.Failure.CANCELLED) {
                    finish(TeleportOutcome.of(Status.CANCELLED_OTHER), true);
                } else {
                    plugin.debug("Live search for " + player.getName() + " failed: "
                            + (result == null ? "null" : result.failure()));
                    noLocation();
                }
            }), () -> tryCancel(Status.CANCELLED_OTHER, null)));
        }

        private void noLocation() {
            plugin.messages().send(player, "teleport.no-location", worldPlaceholder());
            finish(TeleportOutcome.of(Status.NO_LOCATION_FOUND), true);
        }

        // ---- teleport ----------------------------------------------------------------------------------------

        /** Player's thread. */
        private void arrive(Location destination, boolean fromPool) {
            if (finished.get() || phase.get() != Phase.RESOLVING) {
                return;
            }
            Location from = player.getLocation();
            RtpTeleportEvent event = new RtpTeleportEvent(player, from, destination.clone(), request.cause(), fromPool);
            if (!event.callEvent()) {
                plugin.debug("RtpTeleportEvent cancelled for " + player.getName());
                finish(TeleportOutcome.of(Status.CANCELLED_EVENT), true);
                return;
            }
            Location to = event.getTo();
            if (to.getWorld() == null) {
                to.setWorld(world);
            }
            if (!phase.compareAndSet(Phase.RESOLVING, Phase.TELEPORTING)) {
                return;
            }
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            if (!player.getPassengers().isEmpty()) {
                player.eject();
            }
            player.setFallDistance(0f);
            teleportAttempted = true;
            player.teleportAsync(to, TeleportCause.PLUGIN).whenComplete((ok, error) -> onPlayer(player,
                    () -> guard(() -> afterTeleport(Boolean.TRUE.equals(ok) && error == null, error, from, to, fromPool)),
                    () -> finish(TeleportOutcome.of(Status.CANCELLED_OTHER), false)));
        }

        /** Player's thread, after teleportAsync completed. */
        private void afterTeleport(boolean ok, @Nullable Throwable error, Location from, Location to, boolean fromPool) {
            if (finished.get()) {
                return;
            }
            if (!ok) {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "teleportAsync failed for " + player.getName(), error);
                }
                plugin.messages().send(player, "teleport.failed");
                finish(TeleportOutcome.of(Status.TELEPORT_FAILED), true);
                return;
            }
            long waitMillis = Math.max(0, (System.nanoTime() - warmupEndNanos) / 1_000_000L);
            TeleportSettings ts = ws.teleport();
            EffectSettings fx = ts.effects();

            // The player is here now: free the pool's tickets (pool delays release so ours take over).
            PooledLocation e = entry.getAndSet(null);
            if (e != null) {
                plugin.pools().release(e);
            }
            player.setFallDistance(0f);
            safely("protection", () -> protection.arm(uuid, ts.invulnerability(), ts.fallDamageProtection()));
            safely("cooldown", () -> {
                if (!bypassCooldown) {
                    plugin.cooldowns().apply(player, ws);
                }
            });

            TagResolver[] ph = arrivalPlaceholders(to);
            safely("messages", () -> {
                plugin.messages().send(player, "teleport.success", ph);
                if (fx.arrivalTitle()) {
                    plugin.messages().title(player, "teleport.title", "teleport.subtitle", TITLE_TIMES, ph);
                }
                CostHandler.Receipt paid = charged;
                if (paid != null && !paid.isEmpty()) {
                    plugin.messages().send(player, "teleport.charged", Placeholder.component("cost", costs.describe(paid)));
                }
            });
            safely("effects", () -> {
                effects.playSound(player, fx.teleportSound());
                effects.spawnParticles(player, fx.arrivalParticles());
                effects.applyPotions(player, fx.potionEffects());
            });
            safely("commands", () -> CommandRunner.run(plugin, player, to, ts.commands()));
            safely("post event", () -> new RtpPostTeleportEvent(player, from, to, request.cause(), fromPool, waitMillis)
                    .callEvent());
            plugin.debug("RTP " + player.getName() + " -> " + to.getWorld().getName() + " " + to.getBlockX() + ","
                    + to.getBlockY() + "," + to.getBlockZ() + " (pool=" + fromPool + ", wait=" + waitMillis + "ms)");
            finish(new TeleportOutcome(Status.SUCCESS, to.clone(), null), false);
        }

        private TagResolver[] arrivalPlaceholders(Location to) {
            Component biome;
            try {
                biome = Component.translatable(to.getWorld().getBiome(to.getBlockX(), to.getBlockY(), to.getBlockZ()));
            } catch (RuntimeException ex) {
                biome = Component.empty();
            }
            return new TagResolver[]{
                    Placeholder.unparsed("world", to.getWorld().getName()),
                    Placeholder.unparsed("x", Integer.toString(to.getBlockX())),
                    Placeholder.unparsed("y", Integer.toString(to.getBlockY())),
                    Placeholder.unparsed("z", Integer.toString(to.getBlockZ())),
                    Placeholder.component("biome", biome),
                    Placeholder.unparsed("player", player.getName())
            };
        }

        // ---- termination ---------------------------------------------------------------------------------

        /**
         * Cancels while in WARMUP or RESOLVING (not once the teleport is in flight). Sends {@code key} to the player
         * when non-null. Thread-safe.
         */
        boolean tryCancel(Status status, @Nullable String key) {
            while (true) {
                Phase current = phase.get();
                if (current == Phase.TELEPORTING || current == Phase.DONE) {
                    return false;
                }
                if (phase.compareAndSet(current, Phase.DONE)) {
                    break;
                }
            }
            if (key != null) {
                plugin.messages().send(player, key);
            }
            finish(TeleportOutcome.of(status), true);
            return true;
        }

        void abortForShutdown() {
            boolean inFlight = phase.getAndSet(Phase.DONE) == Phase.TELEPORTING;
            finish(TeleportOutcome.of(Status.CANCELLED_OTHER), !inFlight);
        }

        /** Runs {@code action}; any exception fails the request (TELEPORT_FAILED, refund, release). */
        void guard(Runnable action) {
            try {
                action.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error during RTP of " + player.getName(), t);
                if (!finished.get()) {
                    try {
                        plugin.messages().send(player, "teleport.failed");
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                    finish(TeleportOutcome.of(Status.TELEPORT_FAILED), true);
                }
            }
        }

        private void safely(String what, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "RTP post-teleport step '" + what + "' failed for "
                        + player.getName(), e);
            }
        }

        /** The single exit point. Idempotent: only the first call has any effect. Thread-safe. */
        void finish(TeleportOutcome outcome, boolean refund) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            phase.set(Phase.DONE);
            pending.remove(uuid, this);
            ScheduledTask t = timer;
            if (t != null) {
                try {
                    t.cancel();
                } catch (RuntimeException ignored) {
                    // already cancelled / plugin disabling
                }
            }

            PooledLocation e = entry.getAndSet(null);
            if (e != null) {
                try {
                    if (outcome.success() || teleportAttempted) {
                        plugin.pools().release(e);
                    } else {
                        plugin.pools().restore(e);
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "Could not return pooled location #" + e.id(), ex);
                }
            }

            CostHandler.Receipt paid = charged;
            boolean doRefund = refund && !outcome.success() && paid != null && !paid.isEmpty()
                    && ws.cost().refundOnFailure();
            if (doRefund || bossBar != null) {
                Runnable onThread = () -> {
                    hideBossBar();
                    if (doRefund) {
                        costs.refund(player, paid);
                        plugin.messages().send(player, "teleport.refunded",
                                Placeholder.component("cost", costs.describe(paid)));
                    }
                };
                Runnable gone = () -> {
                    if (doRefund) {
                        plugin.getLogger().warning("Could not refund RTP cost to " + player.getName()
                                + " (player left before the refund could run).");
                    }
                };
                try {
                    onPlayer(player, onThread, gone);
                } catch (RuntimeException ex) {
                    // Scheduler unavailable (plugin disabling): best effort inline.
                    try {
                        onThread.run();
                    } catch (RuntimeException ex2) {
                        plugin.getLogger().log(Level.WARNING, "RTP cleanup for " + player.getName() + " failed", ex2);
                    }
                }
            }

            if (initiatorIsOther()) {
                try {
                    if (outcome.success() && outcome.destination() != null) {
                        Location d = outcome.destination();
                        plugin.messages().send(initiator(), "teleport.sent-other-done",
                                Placeholder.unparsed("player", player.getName()),
                                Placeholder.unparsed("world", d.getWorld() == null ? world.getName() : d.getWorld().getName()),
                                Placeholder.unparsed("x", Integer.toString(d.getBlockX())),
                                Placeholder.unparsed("y", Integer.toString(d.getBlockY())),
                                Placeholder.unparsed("z", Integer.toString(d.getBlockZ())));
                    } else {
                        plugin.messages().send(initiator(), "teleport.sent-other-failed",
                                Placeholder.unparsed("player", player.getName()), worldPlaceholder(),
                                Placeholder.component("reason", reason(outcome.status())));
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "Could not notify RTP initiator", ex);
                }
            }
            future.complete(outcome);
        }

        private void hideBossBar() {
            BossBar bar = bossBar;
            if (bar != null) {
                bossBar = null;
                player.hideBossBar(bar);
            }
        }
    }
}

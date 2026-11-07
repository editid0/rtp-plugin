package io.github.editid0.swiftrtp.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thin, Folia-safe wrapper around Paper's region-aware schedulers.
 * <p>
 * On Paper all "region" and "global" tasks run on the main thread; on Folia they run on the thread owning the
 * region. Always use these methods instead of {@code BukkitScheduler} so the plugin works on both.
 * <ul>
 *   <li>{@link #global} – global region; use for server-wide state (no world/entity access on Folia).</li>
 *   <li>{@link #region} – thread owning the given chunk; required for block/chunk access.</li>
 *   <li>{@link #entity} – thread owning the entity; required for player access (teleport, inventory, effects).</li>
 *   <li>{@link #async} – off-thread; never touch world/entity state.</li>
 * </ul>
 * Delays are in ticks unless a {@link Duration} is taken; tick delays &lt; 1 are clamped to 1.
 */
public final class Scheduling {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final Plugin plugin;

    public Scheduling(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    // ---- global ----------------------------------------------------------------------------------------------

    public void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public ScheduledTask globalLater(Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), clamp(delayTicks));
    }

    public ScheduledTask globalTimer(Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, clamp(delayTicks), clamp(periodTicks));
    }

    // ---- region ----------------------------------------------------------------------------------------------

    public void region(World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    public void region(Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    public ScheduledTask regionLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, t -> task.run(), clamp(delayTicks));
    }

    /** Runs {@code task} on the region owning the chunk, or immediately if the current thread already owns it. */
    public void regionOrNow(World world, int chunkX, int chunkZ, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            task.run();
        } else {
            region(world, chunkX, chunkZ, task);
        }
    }

    /** Computes a value on the thread owning the chunk. Exceptions complete the future exceptionally. */
    public <T> CompletableFuture<T> supplyOnRegion(World world, int chunkX, int chunkZ, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        regionOrNow(world, chunkX, chunkZ, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    // ---- entity ----------------------------------------------------------------------------------------------

    /**
     * Runs on the thread owning the entity. {@code retired} runs instead if the entity is removed (e.g. player
     * quit) before the task executes. Returns false if the entity was already retired.
     */
    public boolean entity(Entity entity, Runnable task, @Nullable Runnable retired) {
        return entity.getScheduler().execute(plugin, task, retired, 1L);
    }

    public boolean entityLater(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks) {
        return entity.getScheduler().execute(plugin, task, retired, clamp(delayTicks));
    }

    /** Returns null if the entity is already retired. */
    public @Nullable ScheduledTask entityTimer(Entity entity, Consumer<ScheduledTask> task, @Nullable Runnable retired,
                                               long delayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired, clamp(delayTicks), clamp(periodTicks));
    }

    /** Runs on the entity's thread, or immediately if the current thread already owns it. */
    public void entityOrNow(Entity entity, Runnable task, @Nullable Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            entity(entity, task, retired);
        }
    }

    // ---- async -----------------------------------------------------------------------------------------------

    public void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public ScheduledTask asyncLater(Runnable task, Duration delay) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    public ScheduledTask asyncTimer(Consumer<ScheduledTask> task, Duration delay, Duration period) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task,
                Math.max(1, delay.toMillis()), Math.max(1, period.toMillis()), TimeUnit.MILLISECONDS);
    }

    // ---- misc ------------------------------------------------------------------------------------------------

    public boolean owns(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public boolean owns(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** Cancels every task this plugin scheduled through any scheduler. Call from onDisable. */
    public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private static long clamp(long ticks) {
        return Math.max(1L, ticks);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

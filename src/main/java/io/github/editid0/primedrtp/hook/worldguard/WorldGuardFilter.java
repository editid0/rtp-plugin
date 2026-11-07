package io.github.editid0.primedrtp.hook.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.editid0.primedrtp.api.LocationFilter;
import org.bukkit.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Claim filter rejecting positions inside any WorldGuard region other than {@code __global__}. Only loaded once
 * WorldGuard is confirmed enabled.
 * <p>
 * Region queries are read-only and safe from region threads. The {@link RegionManager} lookup is cached per world
 * (WorldGuard keeps one manager per loaded world for its lifetime); {@link #invalidate()} clears the cache on reload
 * / world unload. Any error allows the position (fail-open) and logs a one-time warning.
 */
public final class WorldGuardFilter implements LocationFilter {

    private static final String GLOBAL = "__global__";

    private final Logger logger;
    private final RegionContainer container;
    private final Map<UUID, Optional<RegionManager>> managers = new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();

    /** @throws LinkageError if the installed WorldGuard/WorldEdit is API-incompatible */
    public WorldGuardFilter(Logger logger) {
        this.logger = logger;
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    @Override
    public String name() {
        return "worldguard";
    }

    @Override
    public boolean isClaimFilter() {
        return true;
    }

    @Override
    public boolean isAllowed(World world, int x, int y, int z) {
        try {
            Optional<RegionManager> manager = managers.computeIfAbsent(world.getUID(),
                    id -> Optional.ofNullable(container.get(BukkitAdapter.adapt(world))));
            if (manager.isEmpty()) {
                return true; // regions disabled for this world
            }
            ApplicableRegionSet set = manager.get().getApplicableRegions(BlockVector3.at(x, y, z));
            if (set.size() == 0) {
                return true;
            }
            for (ProtectedRegion region : set.getRegions()) {
                if (!GLOBAL.equalsIgnoreCase(region.getId())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
                throw vme;
            }
            if (warned.compareAndSet(false, true)) {
                logger.warning("WorldGuard region check failed, allowing locations (warned once): " + t);
            }
            return true;
        }
    }

    /** Drops cached region managers (WorldGuard reload, world unload). */
    public void invalidate() {
        managers.clear();
    }

    /** Drops the cached region manager of one world. */
    public void invalidate(UUID worldId) {
        managers.remove(worldId);
    }
}

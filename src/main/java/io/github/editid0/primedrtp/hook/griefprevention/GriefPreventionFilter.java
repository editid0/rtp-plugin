package io.github.editid0.primedrtp.hook.griefprevention;

import io.github.editid0.primedrtp.api.LocationFilter;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Claim filter rejecting positions inside any GriefPrevention claim (player and admin claims alike). Only loaded once
 * GriefPrevention is confirmed enabled.
 * <p>
 * Height is ignored: GriefPrevention claims reach from their depth limit up to the sky, and a spot in a cave below a
 * claim is still somebody's land, so the whole column of a claim is rejected. {@code DataStore#getClaimAt} is
 * synchronized, so the lookup is safe from any thread. The plugin instance is read on every call, which keeps the
 * filter working across a GriefPrevention reload. Any error allows the position (fail-open) and logs a one-time
 * warning.
 */
public final class GriefPreventionFilter implements LocationFilter {

    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** @throws LinkageError if the installed GriefPrevention is API-incompatible */
    public GriefPreventionFilter(Logger logger) {
        this.logger = logger;
        // Touch the API once so an incompatible version fails here (and only disables this hook), not mid-search.
        GriefPrevention.class.getName();
    }

    @Override
    public String name() {
        return "griefprevention";
    }

    @Override
    public boolean isClaimFilter() {
        return true;
    }

    @Override
    public boolean isAllowed(World world, int x, int y, int z) {
        try {
            GriefPrevention gp = GriefPrevention.instance;
            if (gp == null || !gp.claimsEnabledForWorld(world)) {
                return true;
            }
            DataStore store = gp.dataStore;
            if (store == null) {
                return true;
            }
            return store.getClaimAt(new Location(world, x, y, z), true, null) == null;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
                throw vme;
            }
            if (warned.compareAndSet(false, true)) {
                logger.warning("GriefPrevention claim check failed, allowing locations (warned once): " + t);
            }
            return true;
        }
    }
}

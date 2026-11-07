package io.github.editid0.primedrtp.hook;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.api.LocationFilter;
import io.github.editid0.primedrtp.hook.griefprevention.GriefPreventionFilter;
import io.github.editid0.primedrtp.hook.papi.PrimedRtpExpansion;
import io.github.editid0.primedrtp.hook.vault.VaultEconomyHook;
import io.github.editid0.primedrtp.hook.worldguard.WorldGuardFilter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects Vault, PlaceholderAPI, WorldGuard and GriefPrevention and owns the location-filter registry.
 * <p>
 * Classloading safety: every class touching an optional plugin's API lives in its own subpackage
 * ({@code hook.vault}, {@code hook.papi}, {@code hook.worldguard}, {@code hook.griefprevention}) and is only
 * <em>executed</em> after
 * {@link PluginManager#isPluginEnabled(String)} confirmed the plugin. Their initialisation is wrapped in
 * {@code catch (LinkageError | RuntimeException)} so an API-incompatible version only disables that integration.
 * <p>
 * Thread-safety: filter registry is copy-on-write; economy state is volatile and re-resolved lazily.
 */
public final class DefaultHookManager implements HookManager, Listener {

    private static final String VAULT = "Vault";
    private static final String PAPI = "PlaceholderAPI";
    private static final String WORLDGUARD = "WorldGuard";
    private static final String GRIEFPREVENTION = "GriefPrevention";
    private static final String VAULT_ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";
    /** Minimum gap between economy service lookups while none is registered (GUI refreshes ask every second). */
    private static final long ECONOMY_RETRY_NANOS = 5_000_000_000L;

    private final PrimedRTP plugin;
    private final CopyOnWriteArrayList<LocationFilter> filters = new CopyOnWriteArrayList<>();

    private volatile boolean vaultEnabled;
    private volatile @Nullable EconomyHook economy;
    private volatile long nextEconomyLookup;
    private volatile boolean economyLogged;

    private volatile @Nullable PrimedRtpExpansion expansion;
    private volatile @Nullable WorldGuardFilter worldGuard;
    private volatile @Nullable GriefPreventionFilter griefPrevention;
    private boolean listening;

    public DefaultHookManager(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    @Override
    public void start() {
        PluginManager pm = plugin.getServer().getPluginManager();
        if (!listening) {
            pm.registerEvents(this, plugin);
            listening = true;
        }
        hookVault(pm);
        hookPlaceholderApi(pm);
        hookWorldGuard(pm);
        hookGriefPrevention(pm);
    }

    @Override
    public void reload() {
        // Re-resolve the economy provider and region managers on next use.
        economy = null;
        nextEconomyLookup = 0;
        WorldGuardFilter wg = worldGuard;
        if (wg != null) {
            wg.invalidate();
        }
    }

    @Override
    public void stop() {
        PrimedRtpExpansion exp = expansion;
        expansion = null;
        if (exp != null) {
            try {
                exp.unregister();
            } catch (LinkageError | RuntimeException e) {
                plugin.getLogger().warning("Could not unregister PlaceholderAPI expansion: " + e);
            }
        }
        WorldGuardFilter wg = worldGuard;
        worldGuard = null;
        if (wg != null) {
            filters.remove(wg);
        }
        GriefPreventionFilter gp = griefPrevention;
        griefPrevention = null;
        if (gp != null) {
            filters.remove(gp);
        }
        economy = null;
        vaultEnabled = false;
        HandlerList.unregisterAll(this);
        listening = false;
    }

    // ---- integrations ------------------------------------------------------------------------------------------

    private void hookVault(PluginManager pm) {
        if (!pm.isPluginEnabled(VAULT)) {
            return;
        }
        vaultEnabled = true;
        logHooked(pm, VAULT);
        economyLogged = false;
        if (economy().isEmpty()) {
            plugin.getLogger().info("Vault found but no economy plugin is registered yet; costs in money are "
                    + "skipped until one is.");
        }
    }

    private void hookPlaceholderApi(PluginManager pm) {
        if (!pm.isPluginEnabled(PAPI)) {
            return;
        }
        try {
            PrimedRtpExpansion exp = new PrimedRtpExpansion(plugin);
            if (exp.register()) {
                expansion = exp;
                logHooked(pm, PAPI);
            } else {
                plugin.getLogger().warning("PlaceholderAPI refused to register the primedrtp expansion.");
            }
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Could not hook into PlaceholderAPI (incompatible version?): " + e);
        }
    }

    private void hookWorldGuard(PluginManager pm) {
        if (!pm.isPluginEnabled(WORLDGUARD)) {
            return;
        }
        try {
            WorldGuardFilter wg = new WorldGuardFilter(plugin.getLogger());
            worldGuard = wg;
            registerFilter(wg);
            logHooked(pm, WORLDGUARD);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Could not hook into WorldGuard (incompatible version?): " + e
                    + " — region avoidance is disabled.");
        }
    }

    private void hookGriefPrevention(PluginManager pm) {
        if (!pm.isPluginEnabled(GRIEFPREVENTION)) {
            return;
        }
        try {
            GriefPreventionFilter gp = new GriefPreventionFilter(plugin.getLogger());
            griefPrevention = gp;
            registerFilter(gp);
            logHooked(pm, GRIEFPREVENTION);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Could not hook into GriefPrevention (incompatible version?): " + e
                    + " — claim avoidance is disabled.");
        }
    }

    private void logHooked(PluginManager pm, String name) {
        Plugin p = pm.getPlugin(name);
        String version = p == null ? "?" : p.getPluginMeta().getVersion();
        plugin.getLogger().info("Hooked into " + name + " v" + version);
    }

    // ---- HookManager -------------------------------------------------------------------------------------------

    @Override
    public Optional<EconomyHook> economy() {
        if (!vaultEnabled) {
            return Optional.empty();
        }
        EconomyHook hook = economy;
        if (hook != null) {
            return Optional.of(hook);
        }
        long now = System.nanoTime();
        long next = nextEconomyLookup;
        if (next != 0 && now - next < 0) {
            return Optional.empty();
        }
        nextEconomyLookup = now + ECONOMY_RETRY_NANOS;
        try {
            hook = VaultEconomyHook.lookup();
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Vault economy lookup failed: " + e);
            hook = null;
        }
        economy = hook;
        if (hook != null && !economyLogged) {
            economyLogged = true;
            plugin.getLogger().info("Using Vault economy: " + safeName(hook));
        }
        return Optional.ofNullable(hook);
    }

    @Override
    public List<LocationFilter> filters() {
        return List.copyOf(filters);
    }

    @Override
    public void registerFilter(LocationFilter filter) {
        Objects.requireNonNull(filter, "filter");
        if (filters.addIfAbsent(filter)) {
            plugin.debug("Registered location filter '" + filter.name() + "'");
        }
    }

    @Override
    public void unregisterFilter(LocationFilter filter) {
        if (filter != null && filters.remove(filter)) {
            plugin.debug("Unregistered location filter '" + filter.name() + "'");
        }
    }

    @Override
    public List<String> activeHooks() {
        List<String> out = new ArrayList<>(4);
        if (vaultEnabled) {
            Optional<EconomyHook> eco = economy();
            out.add(eco.map(e -> VAULT + " (" + safeName(e) + ")").orElse(VAULT + " (no economy)"));
        }
        if (expansion != null) {
            out.add(PAPI);
        }
        if (worldGuard != null) {
            out.add(WORLDGUARD);
        }
        if (griefPrevention != null) {
            out.add(GRIEFPREVENTION);
        }
        return List.copyOf(out);
    }

    private static String safeName(EconomyHook hook) {
        try {
            return hook.name();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ---- events ------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        onServiceChange(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        onServiceChange(event);
    }

    /** An economy provider (un)registered: drop the cached hook so the next call picks the current provider. */
    private void onServiceChange(ServiceEvent event) {
        // Compare by name so Vault classes are never loaded when Vault is absent.
        if (vaultEnabled && VAULT_ECONOMY_CLASS.equals(event.getProvider().getService().getName())) {
            economy = null;
            nextEconomyLookup = 0;
            economyLogged = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        WorldGuardFilter wg = worldGuard;
        if (wg != null) {
            wg.invalidate(event.getWorld().getUID());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        WorldGuardFilter wg = worldGuard;
        if (wg != null) {
            wg.invalidate(event.getWorld().getUID());
        }
    }
}

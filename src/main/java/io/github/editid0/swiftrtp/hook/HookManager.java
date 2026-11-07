package io.github.editid0.swiftrtp.hook;

import io.github.editid0.swiftrtp.Service;
import io.github.editid0.swiftrtp.api.LocationFilter;

import java.util.List;
import java.util.Optional;

/**
 * Detects optional plugins (Vault, PlaceholderAPI, WorldGuard) in {@link #start()} and owns the location-filter
 * registry (built-in claim filters + API-registered filters). Integration classes referencing optional APIs must only
 * be loaded after the dependency is confirmed present. Thread-safe.
 */
public interface HookManager extends Service {

    Optional<EconomyHook> economy();

    /** Snapshot of all filters (immutable, safe to iterate from any thread). */
    List<LocationFilter> filters();

    void registerFilter(LocationFilter filter);

    void unregisterFilter(LocationFilter filter);

    /** Names of active integrations, for /rtp admin info. */
    List<String> activeHooks();
}

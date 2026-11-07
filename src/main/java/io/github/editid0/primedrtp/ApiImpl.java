package io.github.editid0.primedrtp;

import io.github.editid0.primedrtp.api.LocationFilter;
import io.github.editid0.primedrtp.api.RtpCause;
import io.github.editid0.primedrtp.api.PrimedRTPApi;
import io.github.editid0.primedrtp.pool.PoolStats;
import io.github.editid0.primedrtp.search.SearchOptions;
import io.github.editid0.primedrtp.search.SearchResult;
import io.github.editid0.primedrtp.teleport.TeleportOutcome;
import io.github.editid0.primedrtp.teleport.TeleportRequest;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ApiImpl implements PrimedRTPApi {

    private final PrimedRTP plugin;

    ApiImpl(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<TeleportOutcome> teleport(Player player, @Nullable World world, boolean force) {
        TeleportRequest request = force
                ? TeleportRequest.forced(player, world, null, RtpCause.API)
                : TeleportRequest.self(player, world, RtpCause.API);
        return plugin.teleports().teleport(request);
    }

    @Override
    public CompletableFuture<Optional<Location>> findLocation(World world) {
        return plugin.settings().world(world.getName())
                .filter(ws -> ws.enabled())
                .map(ws -> plugin.searcher().search(world, ws, SearchOptions.urgent())
                        .thenApply(r -> Optional.ofNullable(r.location())))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

    @Override
    public PoolStats poolStats(World world) {
        return plugin.pools().stats(world);
    }

    @Override
    public void registerFilter(LocationFilter filter) {
        plugin.hooks().registerFilter(filter);
    }

    @Override
    public void unregisterFilter(LocationFilter filter) {
        plugin.hooks().unregisterFilter(filter);
    }

    @SuppressWarnings("unused")
    private static boolean ok(SearchResult r) {
        return r.success();
    }
}

package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.Service;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Runs the full RTP flow: resolve world → permission → cooldown → cost check → {@code RtpPreTeleportEvent} →
 * warmup (cancellable) → charge → take pooled location (or live URGENT search) → re-validate →
 * {@code RtpTeleportEvent} → teleportAsync → protection/effects/commands → cooldown → {@code RtpPostTeleportEvent}.
 * Callable from any thread. Sends all player-facing messages itself.
 */
public interface TeleportService extends Service {

    CompletableFuture<TeleportOutcome> teleport(TeleportRequest request);

    /**
     * World a player would go to with {@code /rtp} and no explicit world: applies world-redirects, then the current
     * world if RTP-enabled, then default-world. Empty if none is usable.
     */
    Optional<World> resolveTargetWorld(Player player, @Nullable World requested);

    boolean hasPending(Player player);

    /** Cancels a pending warmup (outcome CANCELLED_OTHER). Returns false if none was pending. */
    boolean cancel(Player player);
}

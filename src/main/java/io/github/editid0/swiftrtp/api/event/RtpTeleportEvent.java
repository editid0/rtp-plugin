package io.github.editid0.swiftrtp.api.event;

import io.github.editid0.swiftrtp.api.RtpCause;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Fired on the player's thread once a destination is chosen, immediately before the teleport. The destination may be
 * changed (it is not re-validated). Cancelling aborts the teleport and refunds any cost.
 */
@NullMarked
public final class RtpTeleportEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RtpCause cause;
    private final Location from;
    private Location to;
    private final boolean fromPool;
    private boolean cancelled;

    public RtpTeleportEvent(Player player, Location from, Location to, RtpCause cause, boolean fromPool) {
        super(player);
        this.from = from;
        this.to = to;
        this.cause = cause;
        this.fromPool = fromPool;
    }

    public RtpCause getCause() {
        return cause;
    }

    public Location getFrom() {
        return from.clone();
    }

    public Location getTo() {
        return to.clone();
    }

    public void setTo(Location to) {
        this.to = to.clone();
    }

    /** True if the destination came from the pre-generated pool (instant), false if searched live. */
    public boolean isFromPool() {
        return fromPool;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

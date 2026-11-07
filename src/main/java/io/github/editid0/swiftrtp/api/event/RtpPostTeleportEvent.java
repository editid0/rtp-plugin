package io.github.editid0.swiftrtp.api.event;

import io.github.editid0.swiftrtp.api.RtpCause;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.NullMarked;

/** Fired on the player's thread after a successful random teleport. */
@NullMarked
public final class RtpPostTeleportEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RtpCause cause;
    private final Location from;
    private final Location to;
    private final boolean fromPool;
    private final long waitMillis;

    public RtpPostTeleportEvent(Player player, Location from, Location to, RtpCause cause, boolean fromPool, long waitMillis) {
        super(player);
        this.from = from;
        this.to = to;
        this.cause = cause;
        this.fromPool = fromPool;
        this.waitMillis = waitMillis;
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

    public boolean isFromPool() {
        return fromPool;
    }

    /** Millis between the warmup ending and the teleport completing (search/load latency). */
    public long getWaitMillis() {
        return waitMillis;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

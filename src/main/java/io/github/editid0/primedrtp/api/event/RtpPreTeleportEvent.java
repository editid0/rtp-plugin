package io.github.editid0.primedrtp.api.event;

import io.github.editid0.primedrtp.api.RtpCause;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Fired on the player's thread after permission/cooldown/cost checks pass, before the warmup starts and before
 * anything is charged. Cancelling aborts silently (the canceller should message the player). The target world may
 * be changed.
 */
@NullMarked
public final class RtpPreTeleportEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RtpCause cause;
    private World targetWorld;
    private boolean cancelled;

    public RtpPreTeleportEvent(Player player, World targetWorld, RtpCause cause) {
        super(player);
        this.targetWorld = targetWorld;
        this.cause = cause;
    }

    public RtpCause getCause() {
        return cause;
    }

    public World getTargetWorld() {
        return targetWorld;
    }

    public void setTargetWorld(World targetWorld) {
        this.targetWorld = targetWorld;
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

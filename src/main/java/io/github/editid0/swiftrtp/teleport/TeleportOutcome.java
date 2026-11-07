package io.github.editid0.swiftrtp.teleport;

import org.bukkit.Location;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/** Final result of a {@link TeleportService#teleport} call. The service has already messaged the player/initiator. */
public record TeleportOutcome(Status status, @Nullable Location destination, @Nullable Duration cooldownRemaining) {

    public enum Status {
        SUCCESS,
        NO_PERMISSION,
        WORLD_NOT_FOUND,
        WORLD_DISABLED,
        ON_COOLDOWN,
        INSUFFICIENT_FUNDS,
        ALREADY_PENDING,
        CANCELLED_MOVE,
        CANCELLED_DAMAGE,
        CANCELLED_EVENT,
        /** Cancelled by command, quit or plugin shutdown. */
        CANCELLED_OTHER,
        NO_LOCATION_FOUND,
        TELEPORT_FAILED
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public static TeleportOutcome of(Status status) {
        return new TeleportOutcome(status, null, null);
    }
}

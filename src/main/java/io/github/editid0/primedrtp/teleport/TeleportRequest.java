package io.github.editid0.primedrtp.teleport;

import io.github.editid0.primedrtp.api.RtpCause;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * @param player        who is teleported
 * @param targetWorld   explicit world, or null to resolve (redirects / default-world / current world)
 * @param initiator     who asked (receives error feedback when not the player); null = the player
 * @param cause         why
 * @param checkPermission enforce world permission (false for admin/console sends, triggers)
 * @param bypassCooldown skip cooldown check AND don't start a cooldown
 * @param bypassCost     don't charge
 * @param bypassWarmup   teleport without countdown
 */
public record TeleportRequest(Player player, @Nullable World targetWorld, @Nullable CommandSender initiator,
                              RtpCause cause, boolean checkPermission, boolean bypassCooldown, boolean bypassCost,
                              boolean bypassWarmup) {

    /** Normal self-initiated request; bypass flags are additionally granted by bypass permissions. */
    public static TeleportRequest self(Player player, @Nullable World world, RtpCause cause) {
        return new TeleportRequest(player, world, null, cause, true, false, false, false);
    }

    /** Staff/console/trigger request: no permission, cooldown, cost or warmup. */
    public static TeleportRequest forced(Player player, @Nullable World world, @Nullable CommandSender initiator, RtpCause cause) {
        return new TeleportRequest(player, world, initiator, cause, false, true, true, true);
    }
}

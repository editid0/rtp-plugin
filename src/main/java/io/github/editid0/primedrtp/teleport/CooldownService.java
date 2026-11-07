package io.github.editid0.primedrtp.teleport;

import io.github.editid0.primedrtp.Service;
import io.github.editid0.primedrtp.config.WorldSettings;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/** Per-player RTP cooldowns, optionally per world, optionally persisted (storage.persist-cooldowns). Thread-safe. */
public interface CooldownService extends Service {

    /** Remaining cooldown (ZERO if none). Honours primedrtp.bypass.cooldown. */
    Duration remaining(Player player, WorldSettings world);

    /** Cooldown length this player would get for the world (tier permissions applied; ZERO if bypass). */
    Duration cooldownFor(Player player, WorldSettings world);

    /** Starts the cooldown for the player in that world (no-op for ZERO). */
    void apply(Player player, WorldSettings world);

    /** Clears cooldowns: one world when worldName != null, else all. */
    void reset(UUID playerId, @Nullable String worldName);
}

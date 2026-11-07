package io.github.editid0.primedrtp.teleport;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.config.PluginSettings;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Default {@link CooldownService}.
 * <ul>
 *   <li>Length: shortest of {@code cooldown.base} and every tier the player has ({@code primedrtp.cooldown.<tier>});
 *       ZERO with {@code primedrtp.bypass.cooldown}.</li>
 *   <li>Key: the world name when {@code cooldown.per-world}, else the shared key {@code *}.</li>
 *   <li>Persistence ({@code storage.persist-cooldowns}): {@code data/cooldowns.json} is loaded asynchronously on start,
 *       autosaved asynchronously every {@code storage.autosave-interval} when dirty (temp file + atomic move) and
 *       saved synchronously on stop.</li>
 * </ul>
 */
public final class DefaultCooldownService implements CooldownService {

    private final PrimedRTP plugin;
    private final CooldownStore store = new CooldownStore();
    private final Object saveLock = new Object();
    private volatile @Nullable ScheduledTask autosaveTask;

    public DefaultCooldownService(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public void start() {
        if (persist()) {
            plugin.scheduling().async(this::load);
        }
        scheduleAutosave();
    }

    @Override
    public void reload() {
        scheduleAutosave();
    }

    @Override
    public void stop() {
        cancelAutosave();
        if (persist()) {
            save();
        }
    }

    // ---- CooldownService -------------------------------------------------------------------------------------

    @Override
    public Duration remaining(Player player, WorldSettings world) {
        if (player.hasPermission(DefaultTeleportService.PERM_BYPASS_COOLDOWN)) {
            return Duration.ZERO;
        }
        long left = store.remainingMillis(player.getUniqueId(), key(world), System.currentTimeMillis());
        return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
    }

    @Override
    public Duration cooldownFor(Player player, WorldSettings world) {
        if (player.hasPermission(DefaultTeleportService.PERM_BYPASS_COOLDOWN)) {
            return Duration.ZERO;
        }
        WorldSettings.CooldownSettings cd = world.cooldown();
        return CooldownTiers.resolve(cd.base(), cd.tiers(), player::hasPermission);
    }

    @Override
    public void apply(Player player, WorldSettings world) {
        Duration length = cooldownFor(player, world);
        if (!length.isPositive()) {
            return;
        }
        long now = System.currentTimeMillis();
        store.set(player.getUniqueId(), key(world), now + length.toMillis(), now);
    }

    @Override
    public void reset(UUID playerId, @Nullable String worldName) {
        if (worldName == null) {
            store.reset(playerId, null);
            return;
        }
        store.reset(playerId, worldName);
        // A world with shared cooldowns stores its cooldown under the shared key.
        boolean shared = plugin.settings().world(worldName).map(ws -> !ws.cooldown().perWorld()).orElse(false);
        if (shared) {
            store.reset(playerId, CooldownStore.SHARED_KEY);
        }
    }

    static String key(WorldSettings world) {
        return world.cooldown().perWorld() ? world.worldName() : CooldownStore.SHARED_KEY;
    }

    // ---- persistence -----------------------------------------------------------------------------------------

    private boolean persist() {
        PluginSettings s = plugin.settings();
        return s != null && s.storage().persistCooldowns();
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("data").resolve("cooldowns.json");
    }

    private void scheduleAutosave() {
        cancelAutosave();
        PluginSettings s = plugin.settings();
        if (s == null || !s.storage().persistCooldowns()) {
            return;
        }
        Duration interval = s.storage().autosaveInterval();
        if (interval == null || !interval.isPositive()) {
            return;
        }
        autosaveTask = plugin.scheduling().asyncTimer(t -> {
            if (store.isDirty()) {
                save();
            }
        }, interval, interval);
    }

    private void cancelAutosave() {
        ScheduledTask task = autosaveTask;
        autosaveTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            int loaded = store.loadJson(json, System.currentTimeMillis());
            plugin.debug("Loaded " + loaded + " cooldown(s) from " + path.getFileName());
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Could not load cooldowns from " + path, e);
        }
    }

    /** Writes all active cooldowns atomically. Safe to call from any thread; serialised by a lock. */
    private void save() {
        synchronized (saveLock) {
            store.clearDirty();
            long now = System.currentTimeMillis();
            store.prune(now);
            Path path = file();
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(tmp, store.toJson(now), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                store.markDirty();
                plugin.getLogger().log(Level.WARNING, "Could not save cooldowns to " + path, e);
            }
        }
    }
}

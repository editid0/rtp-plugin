package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.config.WorldSettings.ParticleSpec;
import io.github.editid0.swiftrtp.config.WorldSettings.PotionSpec;
import io.github.editid0.swiftrtp.config.WorldSettings.SoundSpec;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Plays configured sounds, particles and potion effects. Registry lookups are cached per key; unknown or unusable
 * keys are warned about once and then ignored. All play methods must run on the player's thread.
 */
final class EffectPlayer {

    private final SwiftRTP plugin;
    private final Map<String, Optional<Sound>> sounds = new ConcurrentHashMap<>();
    private final Map<String, Optional<Particle>> particles = new ConcurrentHashMap<>();
    private final Map<String, Optional<PotionEffectType>> potions = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    EffectPlayer(SwiftRTP plugin) {
        this.plugin = plugin;
    }

    /** Drops cached lookups (config reload may have introduced new keys). */
    void clearCache() {
        sounds.clear();
        particles.clear();
        potions.clear();
        warned.clear();
    }

    void playSound(Player player, @Nullable SoundSpec spec) {
        if (spec == null) {
            return;
        }
        resolve(sounds, RegistryKey.SOUND_EVENT, "sound", spec.key()).ifPresent(sound ->
                player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, spec.volume(), spec.pitch()));
    }

    /** Spawns particles around the player's body, visible to everyone nearby. */
    void spawnParticles(Player player, @Nullable ParticleSpec spec) {
        if (spec == null || spec.count() <= 0) {
            return;
        }
        Optional<Particle> particle = resolve(particles, RegistryKey.PARTICLE_TYPE, "particle", spec.key());
        if (particle.isEmpty()) {
            return;
        }
        Particle type = particle.get();
        if (type.getDataType() != Void.class) {
            warnOnce("particle-data:" + spec.key(), "Particle '" + spec.key()
                    + "' requires extra data and cannot be used for RTP effects; choose another particle.");
            return;
        }
        Location at = player.getLocation().add(0, 1.0, 0);
        double spread = Math.max(0, spec.spread());
        try {
            player.getWorld().spawnParticle(type, at, spec.count(), spread, spread, spread, 0.0);
        } catch (RuntimeException e) {
            warnOnce("particle-error:" + spec.key(), "Could not spawn particle '" + spec.key() + "': " + e.getMessage());
        }
    }

    void applyPotions(Player player, List<PotionSpec> specs) {
        for (PotionSpec spec : specs) {
            Optional<PotionEffectType> type = resolve(potions, RegistryKey.MOB_EFFECT, "potion effect", spec.key());
            if (type.isEmpty()) {
                continue;
            }
            long ticks = MovementCheck.toTicks(spec.duration().toMillis());
            if (ticks <= 0) {
                continue;
            }
            int duration = (int) Math.min(Integer.MAX_VALUE, ticks);
            player.addPotionEffect(new PotionEffect(type.get(), duration, Math.max(0, spec.amplifier()),
                    spec.ambient(), spec.particles(), spec.icon()));
        }
    }

    private <T extends Keyed> Optional<T> resolve(Map<String, Optional<T>> cache, RegistryKey<T> registry,
                                                   String what, String key) {
        return cache.computeIfAbsent(key, k -> {
            try {
                T value = RegistryAccess.registryAccess().getRegistry(registry).get(Key.key(k));
                if (value == null) {
                    warnOnce(what + ":" + k, "Unknown " + what + " '" + k + "' in config; ignoring it.");
                }
                return Optional.ofNullable(value);
            } catch (RuntimeException e) {
                warnOnce(what + ":" + k, "Invalid " + what + " key '" + k + "' in config (" + e.getMessage() + "); ignoring it.");
                return Optional.empty();
            }
        });
    }

    private void warnOnce(String id, String message) {
        if (warned.add(id)) {
            plugin.getLogger().log(Level.WARNING, message);
        }
    }
}

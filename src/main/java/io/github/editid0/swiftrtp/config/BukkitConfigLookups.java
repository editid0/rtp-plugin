package io.github.editid0.swiftrtp.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/** Live {@link ConfigLookups} backed by the running server's materials, tags and registries. Thread-safe. */
final class BukkitConfigLookups implements ConfigLookups {

    @Override
    public @Nullable Material block(String key) {
        Material m = Material.matchMaterial(key);
        return m != null && m.isBlock() && !m.isLegacy() ? m : null;
    }

    @Override
    public @Nullable Material item(String key) {
        Material m = Material.matchMaterial(key);
        return m != null && m.isItem() && !m.isAir() && !m.isLegacy() ? m : null;
    }

    @Override
    public @Nullable Collection<Material> blockTag(String key) {
        NamespacedKey nk = NamespacedKey.fromString(key);
        if (nk == null) {
            return null;
        }
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, nk, Material.class);
        return tag == null ? null : tag.getValues();
    }

    @Override
    public boolean exists(Kind kind, String key) {
        if (!Key.parseable(key)) {
            return false;
        }
        Key k = Key.key(key);
        try {
            RegistryAccess access = RegistryAccess.registryAccess();
            return switch (kind) {
                case SOUND -> access.getRegistry(RegistryKey.SOUND_EVENT).get(k) != null;
                case PARTICLE -> access.getRegistry(RegistryKey.PARTICLE_TYPE).get(k) != null;
                case EFFECT -> access.getRegistry(RegistryKey.MOB_EFFECT).get(k) != null;
                case BIOME -> access.getRegistry(RegistryKey.BIOME).get(k) != null;
            };
        } catch (RuntimeException e) {
            // Registry not available (should not happen on a running server): don't reject the entry.
            return true;
        }
    }
}

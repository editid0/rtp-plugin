package io.github.editid0.primedrtp.pool;

import io.github.editid0.primedrtp.config.WorldSettings;

import java.time.Duration;
import java.util.Set;

/** Builds minimal {@link WorldSettings} for tests (sections the pool ignores are null). */
final class TestSettings {

    private TestSettings() {}

    static WorldSettings.AreaSettings area(int maxRadius) {
        return new WorldSettings.AreaSettings(WorldSettings.CenterMode.FIXED, 0, 0, WorldSettings.Shape.SQUARE,
                0, maxRadius, true, 16);
    }

    static WorldSettings.SearchSettings search(int maxAttempts) {
        return new WorldSettings.SearchSettings(WorldSettings.SearchMode.SURFACE, null, null, maxAttempts, true,
                true, Set.of(), Set.of(), WorldSettings.ChunkPreference.ANY,
                new WorldSettings.BiomeFilter(WorldSettings.FilterMode.BLACKLIST, Set.of("minecraft:ocean", "*river*")),
                false, 0);
    }

    static WorldSettings.PoolSettings pool(int size, Duration expire, int spacing) {
        return new WorldSettings.PoolSettings(true, size, 1, 2, expire, spacing);
    }

    static WorldSettings world(int size) {
        return world(size, Duration.ZERO, 0, 5000, 32);
    }

    static WorldSettings world(int size, Duration expire, int spacing, int maxRadius, int maxAttempts) {
        return new WorldSettings("world", true, null, area(maxRadius), search(maxAttempts),
                pool(size, expire, spacing), null, null, null);
    }
}

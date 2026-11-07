package io.github.editid0.swiftrtp.pool;

import io.github.editid0.swiftrtp.config.WorldSettings;

import java.util.Collection;
import java.util.TreeSet;

/**
 * Deterministic text fingerprint of the settings that decide which locations are valid (area + search). Stored with
 * persisted pools so entries saved under different settings are not restored. Unlike {@code hashCode()} it is stable
 * across JVM runs (enum hash codes are identity based; set iteration order varies), so sets are sorted.
 */
final class SettingsFingerprint {

    private SettingsFingerprint() {}

    static String of(WorldSettings ws) {
        WorldSettings.AreaSettings a = ws.area();
        WorldSettings.SearchSettings s = ws.search();
        StringBuilder sb = new StringBuilder(256);
        sb.append("area:").append(a.centerMode()).append(',').append(a.centerX()).append(',').append(a.centerZ())
                .append(',').append(a.shape()).append(',').append(a.minRadius()).append(',').append(a.maxRadius())
                .append(',').append(a.respectWorldBorder()).append(',').append(a.borderPadding());
        sb.append("|search:").append(s.mode()).append(',').append(s.minY()).append(',').append(s.maxY())
                .append(',').append(s.requireSkyAccess()).append(',').append(s.avoidLiquids())
                .append(',').append(s.chunkPreference()).append(',').append(s.avoidClaims())
                .append(",floor=").append(sorted(s.unsafeFloor()))
                .append(",nearby=").append(sorted(s.unsafeNearby()))
                .append(",biomes=").append(s.biomes().mode()).append(sorted(s.biomes().biomes()));
        // maxAttempts and minDistanceFromPlayers do not change which already-found spots are valid.
        return sb.toString();
    }

    private static TreeSet<String> sorted(Collection<?> values) {
        TreeSet<String> out = new TreeSet<>();
        for (Object v : values) {
            out.add(v instanceof Enum<?> e ? e.name() : String.valueOf(v));
        }
        return out;
    }
}

package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.config.WorldSettings.BiomeFilter;
import io.github.editid0.primedrtp.config.WorldSettings.FilterMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiled {@link BiomeFilter}: decides whether a biome key (e.g. {@code minecraft:plains}) is allowed.
 * <p>
 * Pattern rules (case-insensitive):
 * <ul>
 *   <li>{@code *} matches any run of characters (including {@code :}), {@code ?} matches exactly one.</li>
 *   <li>A pattern containing {@code :} is matched against the full key.</li>
 *   <li>A pattern without {@code :} that starts with {@code *} (e.g. {@code *ocean*}) is matched against the full key,
 *       so it also hits datapack biomes like {@code terralith:warm_ocean}.</li>
 *   <li>Any other pattern without a namespace gets {@code minecraft:} prepended ({@code plains} →
 *       {@code minecraft:plains}, {@code snowy_*} → {@code minecraft:snowy_*}).</li>
 * </ul>
 * An empty list allows everything in both modes (an empty WHITELIST is treated as "allow everything").
 * Thread-safe; results are memoised per key.
 */
final class BiomeMatcher {

    private static final BiomeMatcher ALLOW_ALL = new BiomeMatcher(FilterMode.BLACKLIST, List.of());

    private final FilterMode mode;
    private final List<String> patterns;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    private BiomeMatcher(FilterMode mode, List<String> patterns) {
        this.mode = mode;
        this.patterns = patterns;
    }

    static BiomeMatcher of(BiomeFilter filter) {
        return of(filter.mode(), filter.biomes());
    }

    static BiomeMatcher of(FilterMode mode, Collection<String> rawPatterns) {
        List<String> normalised = new ArrayList<>(rawPatterns.size());
        for (String raw : rawPatterns) {
            if (raw == null) {
                continue;
            }
            String p = normalisePattern(raw);
            if (!p.isEmpty()) {
                normalised.add(p);
            }
        }
        if (normalised.isEmpty()) {
            return ALLOW_ALL;
        }
        return new BiomeMatcher(mode, List.copyOf(normalised));
    }

    /** True when every biome is allowed, so biome lookups can be skipped entirely. */
    boolean isUnrestricted() {
        return patterns.isEmpty();
    }

    boolean allows(String biomeKey) {
        if (patterns.isEmpty()) {
            return true;
        }
        return cache.computeIfAbsent(biomeKey.toLowerCase(Locale.ROOT), key -> {
            boolean matched = false;
            for (String p : patterns) {
                if (glob(p, key)) {
                    matched = true;
                    break;
                }
            }
            return mode == FilterMode.WHITELIST ? matched : !matched;
        });
    }

    static String normalisePattern(String raw) {
        String p = raw.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || p.indexOf(':') >= 0 || p.startsWith("*")) {
            return p;
        }
        return "minecraft:" + p;
    }

    /** Iterative wildcard match supporting {@code *} and {@code ?}; O(n·m) worst case, no regex. */
    static boolean glob(String pattern, String text) {
        int p = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}

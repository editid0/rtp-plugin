package io.github.editid0.swiftrtp.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Pure helpers operating on configuration trees represented as nested {@code Map<String, Object>} (insertion
 * ordered). Values are scalars, lists or nested maps.
 * <p>
 * "Opaque" paths are maps that hold user data rather than schema (e.g. {@code cooldown.tiers},
 * {@code world-redirects}); they are treated as a single value: replaced wholesale on merge and never inspected
 * key-by-key for missing/unknown keys.
 */
public final class ConfigTrees {

    private ConfigTrees() {}

    /** Deep-copies a Bukkit section into plain ordered maps (nested sections become maps, lists are copied). */
    public static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, normalize(value));
        }
        return out;
    }

    /** Converts sections / maps / lists recursively into plain ordered maps and lists. */
    public static Object normalize(Object value) {
        if (value instanceof ConfigurationSection cs) {
            return toMap(cs);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : normalize(v)));
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(o == null ? null : normalize(o));
            }
            return out;
        }
        return value;
    }

    /**
     * Deep merge: returns a new tree where {@code override} is layered over {@code base}. Nested maps merge
     * recursively; scalars, lists and opaque maps from {@code override} replace those of {@code base}. Neither input
     * is modified.
     *
     * @param opaque tests a dotted path relative to the roots (e.g. {@code cooldown.tiers})
     */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override,
                                            Predicate<String> opaque) {
        return merge(base, override, opaque, "");
    }

    /** Deep merge with no opaque paths. */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        return merge(base, override, p -> false, "");
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override,
                                             Predicate<String> opaque, String prefix) {
        Map<String, Object> out = deepCopy(base);
        for (Map.Entry<String, Object> e : override.entrySet()) {
            String path = join(prefix, e.getKey());
            Object o = e.getValue();
            Object b = out.get(e.getKey());
            if (o instanceof Map<?, ?> om && b instanceof Map<?, ?> bm && !opaque.test(path)) {
                out.put(e.getKey(), merge(asMap(bm), asMap(om), opaque, path));
            } else {
                out.put(e.getKey(), copyValue(o));
            }
        }
        return out;
    }

    /**
     * Walks {@code defaults} and reports every path absent from {@code actual}. A missing map is reported once (its
     * path), not per child. Opaque maps that exist in {@code actual} are not descended into.
     *
     * @param skip paths (relative to root) that are neither reported nor descended into
     * @param sink receives (path, default value) for each missing entry
     */
    public static void findMissing(Map<String, Object> defaults, Map<String, Object> actual,
                                   Predicate<String> skip, BiConsumer<String, Object> sink) {
        findMissing(defaults, actual, skip, sink, "");
    }

    private static void findMissing(Map<String, Object> defaults, Map<String, Object> actual, Predicate<String> skip,
                                    BiConsumer<String, Object> sink, String prefix) {
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            String path = join(prefix, e.getKey());
            if (skip.test(path)) {
                continue;
            }
            if (!actual.containsKey(e.getKey())) {
                sink.accept(path, copyValue(e.getValue()));
                continue;
            }
            Object a = actual.get(e.getKey());
            if (e.getValue() instanceof Map<?, ?> dm && a instanceof Map<?, ?> am) {
                findMissing(asMap(dm), asMap(am), skip, sink, path);
            }
        }
    }

    /**
     * Reports every path in {@code actual} that the schema tree {@code schema} doesn't know (likely typos).
     * Opaque maps in the schema are not descended into.
     *
     * @param opaque        tests paths relative to the roots of the walk
     * @param displayPrefix prepended to reported paths (e.g. {@code worlds.world})
     */
    public static void findUnknown(Map<String, Object> schema, Map<String, Object> actual, Predicate<String> opaque,
                                   String displayPrefix, Consumer<String> sink) {
        findUnknown(schema, actual, opaque, displayPrefix, "", sink);
    }

    private static void findUnknown(Map<String, Object> schema, Map<String, Object> actual, Predicate<String> opaque,
                                    String displayPrefix, String relPrefix, Consumer<String> sink) {
        for (Map.Entry<String, Object> e : actual.entrySet()) {
            String display = join(displayPrefix, e.getKey());
            String rel = join(relPrefix, e.getKey());
            if (!schema.containsKey(e.getKey())) {
                sink.accept(display);
                continue;
            }
            if (schema.get(e.getKey()) instanceof Map<?, ?> sm && e.getValue() instanceof Map<?, ?> am
                    && !opaque.test(rel)) {
                findUnknown(asMap(sm), asMap(am), opaque, display, rel, sink);
            }
        }
    }

    /** Value at a dotted path, or null. */
    public static Object get(Map<String, Object> root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    public static Map<String, Object> deepCopy(Map<String, Object> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, copyValue(v)));
        return out;
    }

    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            return deepCopy(asMap(m));
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(copyValue(o));
            }
            return out;
        }
        return v;
    }

    private static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }
}

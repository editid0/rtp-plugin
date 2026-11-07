package io.github.editid0.primedrtp.config;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Typed, path-aware, lenient reader over a plain configuration map. Every accessor takes a fallback; invalid or
 * out-of-range values produce a warning naming the full path (e.g. {@code worlds.world.area.max-radius}) and the
 * fallback or a clamped value is used instead. Never throws for bad values.
 */
public final class ConfigNode {

    private final String path;
    private final Map<String, Object> map;
    private final Consumer<String> warn;

    public ConfigNode(String path, Map<String, Object> map, Consumer<String> warn) {
        this.path = path;
        this.map = map;
        this.warn = warn;
    }

    /** Full dotted path of this node ("" for the root). */
    public String path() {
        return path;
    }

    public Map<String, Object> map() {
        return map;
    }

    public Consumer<String> warnSink() {
        return warn;
    }

    /** Full dotted path of a child key. */
    public String path(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public void warn(String key, String message) {
        warn.accept(path(key) + ": " + message);
    }

    public boolean has(String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    public @Nullable Object raw(String key) {
        return map.get(key);
    }

    /** Child section; missing → empty node, non-map → warning + empty node. */
    public ConfigNode child(String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) {
            return new ConfigNode(path(key), ConfigTrees.asMap(m), warn);
        }
        if (v != null) {
            warn(key, "expected a section, got '" + v + "'; using defaults");
        }
        return new ConfigNode(path(key), Map.of(), warn);
    }

    public boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> {
                warn(key, "expected true/false, got '" + v + "'; using " + def);
                yield def;
            }
        };
    }

    public int integer(String key, int def, int min, int max) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        long value;
        if (v instanceof Number n && !(v instanceof Double || v instanceof Float)) {
            value = n.longValue();
        } else {
            try {
                String s = v.toString().trim();
                double d = Double.parseDouble(s);
                if (d != Math.rint(d)) {
                    warn(key, "expected a whole number, got '" + v + "'; rounding");
                }
                value = Math.round(d);
            } catch (NumberFormatException e) {
                warn(key, "expected a whole number, got '" + v + "'; using " + def);
                return def;
            }
        }
        if (value < min) {
            warn(key, value + " is below the minimum " + min + "; using " + min);
            return min;
        }
        if (value > max) {
            warn(key, value + " is above the maximum " + max + "; using " + max);
            return max;
        }
        return (int) value;
    }

    /** Integer that may be "auto"/""/null → null. */
    public @Nullable Integer optInteger(String key, @Nullable Integer def, int min, int max) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto") || s.equals("default") || s.equals("none") || s.equals("~")) {
            return null;
        }
        return integer(key, def == null ? 0 : def, min, max);
    }

    public double decimal(String key, double def, double min, double max) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        double value;
        if (v instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException e) {
                warn(key, "expected a number, got '" + v + "'; using " + def);
                return def;
            }
        }
        if (Double.isNaN(value)) {
            warn(key, "NaN is not allowed; using " + def);
            return def;
        }
        if (value < min) {
            warn(key, value + " is below the minimum " + min + "; using " + min);
            return min;
        }
        if (value > max) {
            warn(key, value + " is above the maximum " + max + "; using " + max);
            return max;
        }
        return value;
    }

    public String string(String key, String def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
            warn(key, "expected text, got a " + (v instanceof Map ? "section" : "list") + "; using '" + def + "'");
            return def;
        }
        return v.toString();
    }

    /** Text where missing / blank / "none" means null. */
    public @Nullable String optString(String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = string(key, "").trim();
        return s.isEmpty() || s.equalsIgnoreCase("none") ? null : s;
    }

    /** Non-negative duration; negative values clamp to zero with a warning. */
    public Duration duration(String key, Duration def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        Duration d;
        try {
            d = DurationParser.parse(v);
        } catch (IllegalArgumentException e) {
            warn(key, e.getMessage() + "; using " + def.toMillis() / 1000.0 + "s");
            return def;
        }
        if (d.isNegative()) {
            warn(key, "negative durations are not allowed; using 0");
            return Duration.ZERO;
        }
        return d;
    }

    /** Case-insensitive enum lookup tolerant of '-' vs '_' and spaces. */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        E parsed = parseEnum(v.toString(), type);
        if (parsed == null) {
            List<String> names = new ArrayList<>();
            for (E c : type.getEnumConstants()) {
                names.add(c.name().toLowerCase(Locale.ROOT));
            }
            warn(key, "unknown value '" + v + "' (expected one of " + String.join(", ", names) + "); using "
                    + def.name().toLowerCase(Locale.ROOT));
            return def;
        }
        return parsed;
    }

    public static <E extends Enum<E>> @Nullable E parseEnum(String text, Class<E> type) {
        String norm = text.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (E c : type.getEnumConstants()) {
            if (c.name().equals(norm)) {
                return c;
            }
        }
        return null;
    }

    /**
     * List of raw elements. A single scalar is accepted as a one-element list; missing → empty list
     * (callers merge with defaults beforehand, so "missing" really means "not configured").
     */
    public List<Object> list(String key) {
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                if (o != null) {
                    out.add(o);
                }
            }
            return out;
        }
        if (v instanceof Map<?, ?>) {
            warn(key, "expected a list, got a section; ignoring");
            return List.of();
        }
        String s = v.toString().trim();
        return s.isEmpty() ? List.of() : List.of(v);
    }

    /** List of strings (non-string elements are converted; sections are skipped with a warning). */
    public List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(key)) {
            if (o instanceof Map<?, ?> || o instanceof Collection<?>) {
                warn(key, "ignoring non-text entry '" + o + "'");
                continue;
            }
            out.add(o.toString());
        }
        return out;
    }
}

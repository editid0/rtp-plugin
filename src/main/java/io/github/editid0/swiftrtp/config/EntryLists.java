package io.github.editid0.swiftrtp.config;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pure helpers for list-style config entries: material/tag sets, biome patterns, namespaced keys and
 * {@code item:amount} costs.
 */
public final class EntryLists {

    private EntryLists() {}

    /**
     * Normalises a namespaced key: trims, lower-cases, strips a leading '#', and adds {@code minecraft:} when no
     * namespace is given. Spaces become underscores ({@code "Magma Block"} → {@code minecraft:magma_block}).
     */
    public static String normalizeKey(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    /** True if the string is a valid namespaced key ({@code namespace:value}, Minecraft charset). */
    public static boolean isValidKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return false;
        }
        String ns = key.substring(0, colon);
        String value = key.substring(colon + 1);
        return ns.chars().allMatch(c -> c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
                && value.chars().allMatch(c -> c == '_' || c == '-' || c == '.' || c == '/'
                || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
    }

    /**
     * Resolves a list of entries into a set. Supported entries:
     * <ul>
     *   <li>{@code lava}, {@code minecraft:lava}, {@code LAVA} — a single value</li>
     *   <li>{@code #leaves}, {@code #minecraft:leaves} — every value of a tag</li>
     *   <li>{@code !oak_leaves} / {@code !#leaves} — remove previously added values (processed in order)</li>
     * </ul>
     * Unknown entries produce a warning (prefixed with {@code path}) and are skipped.
     *
     * @param single resolves a normalised key, or null if unknown
     * @param tag    resolves a normalised tag key, or null if unknown
     */
    public static <T> Set<T> resolve(List<String> entries, String path, Consumer<String> warn,
                                     Function<String, @Nullable T> single,
                                     Function<String, @Nullable Collection<T>> tag) {
        Set<T> out = new LinkedHashSet<>();
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            boolean remove = entry.startsWith("!");
            if (remove) {
                entry = entry.substring(1).trim();
            }
            boolean isTag = entry.startsWith("#");
            String key = normalizeKey(entry);
            if (!isValidKey(key)) {
                warn.accept(path + ": '" + raw + "' is not a valid " + (isTag ? "tag" : "name") + "; skipped");
                continue;
            }
            if (isTag) {
                Collection<T> values = tag.apply(key);
                if (values == null) {
                    warn.accept(path + ": unknown block tag '#" + key + "'; skipped");
                    continue;
                }
                if (remove) {
                    out.removeAll(values);
                } else {
                    out.addAll(values);
                }
            } else {
                T value = single.apply(key);
                if (value == null) {
                    warn.accept(path + ": unknown entry '" + raw + "'; skipped");
                    continue;
                }
                if (remove) {
                    out.remove(value);
                } else {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /** True if the biome entry contains glob characters. */
    public static boolean isGlob(String entry) {
        return entry.indexOf('*') >= 0 || entry.indexOf('?') >= 0;
    }

    /**
     * Normalises a biome entry: lower-case, trimmed; a plain name gains {@code minecraft:}; globs without a namespace
     * are kept as-is so they match any namespace ({@code *ocean*} matches {@code minecraft:deep_ocean} and modded
     * oceans). Returns null for blank entries.
     */
    public static @Nullable String normalizeBiome(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.isEmpty()) {
            return null;
        }
        if (isGlob(s) && s.indexOf(':') < 0) {
            return s;
        }
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    /** Parsed {@code material:amount}; material is a normalised key. */
    public record ItemEntry(String materialKey, int amount) {}

    /**
     * Parses {@code "diamond"}, {@code "diamond:2"}, {@code "minecraft:diamond:2"}, {@code "diamond x2"} or
     * {@code "2x diamond"}. Returns null if malformed (amount < 1 or not a number).
     */
    public static @Nullable ItemEntry parseItem(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int amount = 1;
        String name = s;
        int lastColon = s.lastIndexOf(':');
        java.util.regex.Matcher pre = java.util.regex.Pattern.compile("(?i)^(\\d+)\\s*x\\s+(.+)$").matcher(s);
        java.util.regex.Matcher post = java.util.regex.Pattern.compile("(?i)^(.+?)\\s+x\\s*(\\d+)$").matcher(s);
        if (pre.matches()) {
            amount = parsePositive(pre.group(1));
            name = pre.group(2);
        } else if (post.matches()) {
            name = post.group(1);
            amount = parsePositive(post.group(2));
        } else if (lastColon > 0 && s.substring(lastColon + 1).trim().matches("-?\\d+")) {
            name = s.substring(0, lastColon);
            amount = parsePositive(s.substring(lastColon + 1).trim());
        }
        if (amount < 1) {
            return null;
        }
        String key = normalizeKey(name);
        return isValidKey(key) ? new ItemEntry(key, amount) : null;
    }

    private static int parsePositive(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Validates a command alias: strips '/', lower-cases; null if invalid. */
    public static @Nullable String normalizeAlias(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s.matches("[a-z0-9_\\-]+") ? s : null;
    }
}

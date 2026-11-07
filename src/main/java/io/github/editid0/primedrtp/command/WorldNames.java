package io.github.editid0.primedrtp.command;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure helpers for the world arguments of {@code /rtp}: which worlds a sender may pick, prefix filtering and quoting
 * for Brigadier's {@code StringArgumentType.string()} (so worlds named like a subcommand, e.g. "admin", or with unusual
 * characters stay reachable as {@code /rtp "admin"}). No Bukkit statics; unit-tested.
 */
public final class WorldNames {

    /** What the command layer needs to know about a world's RTP config. */
    public record Access(boolean enabled, @Nullable String permission) {}

    private WorldNames() {}

    /**
     * Worlds (in input order) that are RTP-enabled and whose permission the sender has (or that need none).
     *
     * @param names         loaded world names
     * @param lookup        world name → RTP access info, empty if the world is not RTP-configured
     * @param hasPermission permission check for the sender
     */
    public static List<String> selectable(Collection<String> names, Function<String, Optional<Access>> lookup,
                                          Predicate<String> hasPermission) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            Optional<Access> access = lookup.apply(name);
            if (access.isEmpty() || !access.get().enabled()) {
                continue;
            }
            String perm = access.get().permission();
            if (perm == null || perm.isBlank() || hasPermission.test(perm)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Same as Brigadier's {@code StringReader.isAllowedInUnquotedString}. */
    static boolean allowedUnquoted(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || c == '_' || c == '-' || c == '.' || c == '+';
    }

    /**
     * The text to suggest for a world: the bare name if it parses unquoted and does not clash with a literal
     * subcommand ({@code reserved}, compared case-insensitively), otherwise a double-quoted, escaped form.
     */
    public static String suggestionText(String name, Set<String> reserved) {
        boolean needsQuotes = name.isEmpty() || reserved.contains(name.toLowerCase(Locale.ROOT));
        for (int i = 0; i < name.length() && !needsQuotes; i++) {
            if (!allowedUnquoted(name.charAt(i))) {
                needsQuotes = true;
            }
        }
        if (!needsQuotes) {
            return name;
        }
        return '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Whether a candidate matches what the user typed so far. Case-insensitive prefix match; a leading quote in the
     * typed text is ignored so {@code "adm} still matches {@code admin}.
     */
    public static boolean matchesPrefix(String candidate, String typed) {
        String t = typed.startsWith("\"") ? typed.substring(1) : typed;
        return candidate.toLowerCase(Locale.ROOT).startsWith(t.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a typed world name against known names: exact match first, then a unique case-insensitive match.
     *
     * @return the canonical name, or null if none / ambiguous
     */
    public static @Nullable String resolve(String typed, Collection<String> names) {
        if (names.contains(typed)) {
            return typed;
        }
        String found = null;
        for (String name : names) {
            if (name.equalsIgnoreCase(typed)) {
                if (found != null) {
                    return null; // ambiguous
                }
                found = name;
            }
        }
        return found;
    }
}

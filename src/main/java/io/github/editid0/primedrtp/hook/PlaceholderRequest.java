package io.github.editid0.primedrtp.hook;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Parsed {@code %primedrtp_<params>%} placeholder. Pure (unit-tested); has no PlaceholderAPI dependency.
 * <p>
 * Syntax: {@code <kind>} or {@code <kind>_<world>}. World names may contain underscores ({@code world_nether}), so
 * the longest matching known prefix wins and the entire remainder is the world name, verbatim. Without a world,
 * world-specific kinds use the player's resolved target world (what a bare {@code /rtp} would pick).
 *
 * @param kind  which value
 * @param world explicit world name, or null for the resolved target world
 */
public record PlaceholderRequest(Kind kind, @Nullable String world) {

    public enum Kind {
        /** Formatted remaining cooldown ({@code time.now} from messages.yml when ready). */
        COOLDOWN("cooldown"),
        /** Remaining cooldown in whole seconds (rounded up). */
        COOLDOWN_SECONDS("cooldown_seconds"),
        /** {@code true}/{@code false}: not on cooldown. */
        READY("ready"),
        POOL_SIZE("pool_size"),
        POOL_TARGET("pool_target"),
        /** 0-100. */
        POOL_PERCENT("pool_percent"),
        /** {@code true}/{@code false}: player has a pending (warmup) teleport. Takes no world. */
        PENDING("pending");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }

        public boolean takesWorld() {
            return this != PENDING;
        }
    }

    /** Kinds sorted longest prefix first, so "cooldown_seconds_x" isn't read as cooldown for world "seconds_x". */
    private static final List<Kind> BY_PREFIX_LENGTH = java.util.Arrays.stream(Kind.values())
            .sorted((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()))
            .toList();

    /**
     * Parses the part after {@code primedrtp_}. The kind is matched case-insensitively; the world name keeps its case.
     *
     * @return the request, or null for unknown placeholders
     */
    public static @Nullable PlaceholderRequest parse(@Nullable String params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String lower = params.toLowerCase(Locale.ROOT);
        for (Kind kind : BY_PREFIX_LENGTH) {
            String p = kind.prefix;
            if (lower.equals(p)) {
                return new PlaceholderRequest(kind, null);
            }
            if (kind.takesWorld() && lower.startsWith(p + "_") && params.length() > p.length() + 1) {
                return new PlaceholderRequest(kind, params.substring(p.length() + 1));
            }
        }
        return null;
    }

    /** Placeholder names for PlaceholderAPI's /papi info listing. */
    public static List<String> documented() {
        return List.of("%primedrtp_cooldown%", "%primedrtp_cooldown_<world>%", "%primedrtp_cooldown_seconds%",
                "%primedrtp_cooldown_seconds_<world>%", "%primedrtp_ready%", "%primedrtp_ready_<world>%",
                "%primedrtp_pool_size_<world>%", "%primedrtp_pool_target_<world>%", "%primedrtp_pool_percent_<world>%",
                "%primedrtp_pending%");
    }
}

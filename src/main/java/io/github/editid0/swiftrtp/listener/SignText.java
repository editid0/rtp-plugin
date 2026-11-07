package io.github.editid0.swiftrtp.listener;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Pure text helpers for RTP signs (no Bukkit statics; unit-tested). */
public final class SignText {

    private SignText() {}

    /**
     * True if the first line of a sign, as typed by the player, is the configured creation header. Comparison is
     * trimmed and case-insensitive; a blank header never matches (signs effectively disabled).
     */
    public static boolean isHeader(@Nullable String plainLine, @Nullable String header) {
        if (plainLine == null || header == null || header.isBlank()) {
            return false;
        }
        return plainLine.trim().toLowerCase(Locale.ROOT).equals(header.trim().toLowerCase(Locale.ROOT));
    }

    /** The world name written on line 2, trimmed; empty means "the player's current world". */
    public static String worldLine(@Nullable String plainLine) {
        return plainLine == null ? "" : plainLine.trim();
    }
}

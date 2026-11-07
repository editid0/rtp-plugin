package io.github.editid0.swiftrtp.gui;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Click action of a static gui.yml button. Parsed from strings such as {@code close}, {@code rtp:world_nether},
 * {@code rtp} (resolved target world), {@code command:spawn} (run as the player) or {@code console:say hi}.
 * Command strings may contain {@code {player}}, {@code {uuid}} and {@code {world}}.
 */
public sealed interface MenuAction {

    /** Closes the menu. */
    record Close() implements MenuAction {}

    /** Random-teleports the clicking player; {@code world == null} lets the teleport service resolve the world. */
    record Rtp(@Nullable String world) implements MenuAction {}

    /** Runs a command as the clicking player (no leading slash needed). */
    record PlayerCommand(String command) implements MenuAction {}

    /** Runs a command as the console. */
    record ConsoleCommand(String command) implements MenuAction {}

    /**
     * Parses an action string.
     *
     * @return the action, or null if the string is not a valid action
     */
    static @Nullable MenuAction parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        int colon = s.indexOf(':');
        String type = (colon < 0 ? s : s.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        String arg = colon < 0 ? "" : s.substring(colon + 1).strip();
        return switch (type) {
            case "close" -> new Close();
            case "rtp" -> new Rtp(arg.isEmpty() ? null : arg);
            case "command", "player" -> arg.isEmpty() ? null : new PlayerCommand(stripSlash(arg));
            case "console" -> arg.isEmpty() ? null : new ConsoleCommand(stripSlash(arg));
            default -> null;
        };
    }

    /** Replaces {@code {player}}, {@code {uuid}} and {@code {world}} in a command template. */
    static String expand(String command, String player, String uuid, String world) {
        return command.replace("{player}", player).replace("{uuid}", uuid).replace("{world}", world);
    }

    private static String stripSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}

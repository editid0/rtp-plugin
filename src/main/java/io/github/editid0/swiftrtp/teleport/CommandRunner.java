package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.SwiftRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Runs {@code teleport.commands} after a successful teleport.
 * <p>
 * Syntax per entry: {@code "console: <cmd>"}, {@code "player: <cmd>"} or {@code "op: <cmd>"}; an entry without a
 * prefix runs as console. A leading {@code /} is stripped. Placeholders: {@code {player} {uuid} {world} {x} {y} {z}}.
 * <ul>
 *   <li>{@code console} — dispatched as the console on the global region thread.</li>
 *   <li>{@code player} — dispatched as the player on the player's thread (player's own permissions).</li>
 *   <li>{@code op} — <b>also dispatched as the console</b>. Temporarily opping a player (or granting a wildcard
 *       attachment) opens privilege-escalation windows (a command that runs other commands, a crash mid-dispatch,
 *       vanilla commands checking op level rather than permissions). Console execution with {@code {player}}
 *       placeholders covers every legitimate use safely.</li>
 * </ul>
 */
public final class CommandRunner {

    public enum Executor { CONSOLE, PLAYER, OP }

    /** A parsed command entry. */
    public record ParsedCommand(Executor executor, String command) {}

    private CommandRunner() {}

    /** Parses one config entry; returns null for blank entries. */
    public static @Nullable ParsedCommand parse(@Nullable String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.strip();
        Executor executor = Executor.CONSOLE;
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            Executor parsed = switch (prefix) {
                case "console" -> Executor.CONSOLE;
                case "player" -> Executor.PLAYER;
                case "op" -> Executor.OP;
                default -> null;
            };
            if (parsed != null) {
                executor = parsed;
                trimmed = trimmed.substring(colon + 1).strip();
            }
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        trimmed = trimmed.strip();
        return trimmed.isEmpty() ? null : new ParsedCommand(executor, trimmed);
    }

    /** Replaces every {@code {key}} occurrence with its value (single pass, values are not re-scanned). */
    public static String applyPlaceholders(String command, Map<String, String> placeholders) {
        if (command.indexOf('{') < 0 || placeholders.isEmpty()) {
            return command;
        }
        StringBuilder out = new StringBuilder(command.length() + 16);
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if (c == '{') {
                int close = command.indexOf('}', i + 1);
                if (close > i) {
                    String value = placeholders.get(command.substring(i + 1, close));
                    if (value != null) {
                        out.append(value);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Placeholder map for a player arriving at a location. */
    public static Map<String, String> placeholders(String playerName, String uuid, String world, int x, int y, int z) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", playerName);
        map.put("uuid", uuid);
        map.put("world", world);
        map.put("x", Integer.toString(x));
        map.put("y", Integer.toString(y));
        map.put("z", Integer.toString(z));
        return map;
    }

    /** Parses and resolves all entries. */
    public static List<ParsedCommand> prepare(List<String> entries, Map<String, String> placeholders) {
        List<ParsedCommand> out = new ArrayList<>(entries.size());
        for (String entry : entries) {
            ParsedCommand parsed = parse(entry);
            if (parsed != null) {
                out.add(new ParsedCommand(parsed.executor(), applyPlaceholders(parsed.command(), placeholders)));
            }
        }
        return out;
    }

    /** Runs the commands. Must be called on the player's thread (player commands run inline). */
    public static void run(SwiftRTP plugin, Player player, Location destination, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<String, String> placeholders = placeholders(player.getName(), player.getUniqueId().toString(),
                destination.getWorld() == null ? "" : destination.getWorld().getName(),
                destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
        for (ParsedCommand command : prepare(entries, placeholders)) {
            switch (command.executor()) {
                case PLAYER -> dispatch(plugin, () -> player.performCommand(command.command()), command);
                case CONSOLE, OP -> plugin.scheduling().global(() ->
                        dispatch(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.command()), command));
            }
        }
    }

    private static void dispatch(SwiftRTP plugin, Runnable action, ParsedCommand command) {
        try {
            plugin.debug("Running " + command.executor() + " command: " + command.command());
            action.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Post-teleport command failed: " + command.command(), e);
        }
    }
}

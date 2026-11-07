package io.github.editid0.primedrtp.config;

import io.github.editid0.primedrtp.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * MiniMessage-backed messages loaded from messages.yml. Keys are dotted paths (e.g. {@code teleport.warmup}).
 * Every message may use {@code <prefix>}, resolved globally. A message set to an empty string is not sent.
 * Unknown keys render as the key itself (and log once in debug mode). All methods are thread-safe.
 * <p>
 * Placeholders are passed as MiniMessage tag resolvers, e.g.
 * {@code Placeholder.unparsed("world", name)}, {@code Formatter.number("x", x)}.
 */
public interface MessageService extends Service {

    void send(CommandSender to, String key, TagResolver... placeholders);

    Component get(String key, TagResolver... placeholders);

    /** Raw (unparsed) MiniMessage string for a key, or null if missing. */
    String raw(String key);

    boolean has(String key);

    void actionBar(Player player, String key, TagResolver... placeholders);

    void title(Player player, String titleKey, String subtitleKey, Title.Times times, TagResolver... placeholders);

    /** Human-friendly duration, e.g. "1h 5m 3s" / "3s" (uses messages.yml time-format units). */
    String formatDuration(java.time.Duration duration);
}

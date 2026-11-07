package io.github.editid0.primedrtp.config;

import io.github.editid0.primedrtp.PrimedRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * {@link MessageService} backed by {@code plugins/PrimedRTP/messages.yml}.
 * <p>
 * On load the user's file is created from the bundled default if missing, and any keys present in the bundled
 * default but not in the user's file are added (with their comments) and written back, so plugin updates deliver new
 * messages automatically. If the user's file has a YAML error, the bundled messages are used and a warning is logged.
 * <p>
 * State is an immutable snapshot swapped atomically on reload; placeholder-free messages are parsed once and cached.
 * All methods are thread-safe.
 */
public final class YamlMessageService implements MessageService {

    private static final String FILE_NAME = "messages.yml";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Immutable view of the loaded messages plus a cache of parsed placeholder-free components. */
    private record State(Map<String, String> raw, TagResolver prefix, DurationFormatter.Units units,
                         ConcurrentHashMap<String, Component> cache) {}

    private final PrimedRTP plugin;
    private volatile State state = new State(Map.of(), prefixResolver(Component.empty()), DurationFormatter.Units.DEFAULT,
            new ConcurrentHashMap<>());
    /** Unknown keys already reported (debug mode), so each is logged once. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    public YamlMessageService(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        load();
    }

    @Override
    public void reload() {
        load();
    }

    // ---------------------------------------------------------------------------------------------------------
    // loading
    // ---------------------------------------------------------------------------------------------------------

    private void load() {
        YamlConfiguration bundled = loadBundled();
        YamlConfiguration user = bundled;
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        try {
            if (!file.isFile()) {
                plugin.saveResource(FILE_NAME, false);
            }
            YamlConfiguration loaded = ConfigLoader.newYaml();
            loaded.load(file);
            List<String> added = ConfigMigrator.fillMissing(loaded, bundled, p -> false);
            if (!added.isEmpty()) {
                try {
                    loaded.save(file);
                    plugin.getLogger().info("Added " + added.size() + " new message(s) to " + FILE_NAME + ".");
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not update " + FILE_NAME + ": " + e.getMessage());
                }
            }
            user = loaded;
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning(FILE_NAME + " has a YAML syntax error, using built-in messages: "
                    + e.getMessage().replaceAll("\\s+", " "));
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + FILE_NAME + ", using built-in messages", e);
        }

        Map<String, String> raw = flatten(user);
        // Keys the user broke (e.g. turned into a section) fall back to the bundled text.
        flatten(bundled).forEach(raw::putIfAbsent);

        String prefixText = raw.getOrDefault("prefix", "");
        Component prefix;
        try {
            prefix = MINI.deserialize(prefixText);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(FILE_NAME + ": invalid prefix (" + e.getMessage() + ")");
            prefix = Component.text(prefixText);
        }
        DurationFormatter.Units units = new DurationFormatter.Units(
                raw.getOrDefault("time.days", "d"), raw.getOrDefault("time.hours", "h"),
                raw.getOrDefault("time.minutes", "m"), raw.getOrDefault("time.seconds", "s"),
                raw.getOrDefault("time.separator", " "), raw.getOrDefault("time.now", "now"));
        state = new State(Map.copyOf(raw), prefixResolver(prefix), units, new ConcurrentHashMap<>());
        reportedMissing.clear();
    }

    private YamlConfiguration loadBundled() {
        YamlConfiguration yaml = ConfigLoader.newYaml();
        try (InputStream in = plugin.getResource(FILE_NAME)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    yaml.load(reader);
                }
            } else {
                plugin.getLogger().severe("Bundled " + FILE_NAME + " is missing from the plugin jar");
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Bundled " + FILE_NAME + " is unreadable", e);
        }
        return yaml;
    }

    /** Leaf values as dotted keys; lists become newline-joined text. */
    private static Map<String, String> flatten(YamlConfiguration yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) {
                continue;
            }
            Object v = yaml.get(key);
            if (v instanceof Collection<?> list) {
                out.put(key, String.join("\n", list.stream().map(String::valueOf).toList()));
            } else if (v != null) {
                out.put(key, v.toString());
            }
        }
        return out;
    }

    private static TagResolver prefixResolver(Component prefix) {
        return TagResolver.resolver("prefix", Tag.selfClosingInserting(prefix));
    }

    // ---------------------------------------------------------------------------------------------------------
    // rendering
    // ---------------------------------------------------------------------------------------------------------

    /** Parsed component, or null if the message is disabled (empty string). */
    private @Nullable Component render(String key, TagResolver... placeholders) {
        State s = state;
        String text = s.raw().get(key);
        if (text == null) {
            if (reportedMissing.add(key)) {
                plugin.debug("Unknown message key '" + key + "' (missing from " + FILE_NAME + ")");
            }
            return Component.text(key);
        }
        if (text.isEmpty()) {
            return null;
        }
        if (placeholders.length == 0) {
            return s.cache().computeIfAbsent(key, k -> parse(k, text, s.prefix()));
        }
        TagResolver resolver = TagResolver.builder().resolver(s.prefix()).resolvers(placeholders).build();
        return parse(key, text, resolver);
    }

    private Component parse(String key, String text, TagResolver resolver) {
        try {
            return MINI.deserialize(text, resolver);
        } catch (RuntimeException e) {
            plugin.debug("Invalid MiniMessage in '" + key + "': " + e.getMessage());
            return Component.text(text);
        }
    }

    @Override
    public void send(CommandSender to, String key, TagResolver... placeholders) {
        Component c = render(key, placeholders);
        if (c != null) {
            to.sendMessage(c);
        }
    }

    @Override
    public Component get(String key, TagResolver... placeholders) {
        Component c = render(key, placeholders);
        return c == null ? Component.empty() : c;
    }

    @Override
    public @Nullable String raw(String key) {
        return state.raw().get(key);
    }

    @Override
    public boolean has(String key) {
        return state.raw().containsKey(key);
    }

    @Override
    public void actionBar(Player player, String key, TagResolver... placeholders) {
        Component c = render(key, placeholders);
        if (c == null) {
            return;
        }
        plugin.scheduling().entityOrNow(player, () -> player.sendActionBar(c), null);
    }

    @Override
    public void title(Player player, String titleKey, String subtitleKey, Title.@Nullable Times times,
                      TagResolver... placeholders) {
        Component title = render(titleKey, placeholders);
        Component subtitle = render(subtitleKey, placeholders);
        if (title == null && subtitle == null) {
            return;
        }
        Title t = Title.title(title == null ? Component.empty() : title,
                subtitle == null ? Component.empty() : subtitle,
                times == null ? Title.DEFAULT_TIMES : times);
        plugin.scheduling().entityOrNow(player, () -> player.showTitle(t), null);
    }

    @Override
    public String formatDuration(Duration duration) {
        return DurationFormatter.format(duration, state.units());
    }

}

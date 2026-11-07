package io.github.editid0.primedrtp.config;

import io.github.editid0.primedrtp.PrimedRTP;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads config.yml into {@link PluginSettings}:
 * <ol>
 *   <li>saves the bundled default if the file is missing;</li>
 *   <li>parses YAML (syntax errors are fatal → {@link ConfigException});</li>
 *   <li>migrates old schema versions ({@link ConfigMigrator}) and back-fills newly added options with their
 *       comments, backing the old file up as {@code config.yml.bak-<n>} before rewriting it;</li>
 *   <li>parses and validates everything ({@link SettingsParser}); recoverable problems are logged as warnings naming
 *       the offending path, and a safe value is used instead.</li>
 * </ol>
 * File IO is synchronous: this runs during enable and {@code /rtp admin reload}, and the caller needs the result.
 */
public final class ConfigLoader {

    private static final String FILE_NAME = "config.yml";

    private final PrimedRTP plugin;

    public ConfigLoader(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    /**
     * Saves default config.yml if missing, migrates old versions, parses and validates it.
     * Logs warnings for recoverable problems (falls back to defaults); throws for fatal ones.
     */
    public PluginSettings load() throws ConfigException {
        Logger log = plugin.getLogger();
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            throw new ConfigException("Could not create plugin folder " + dataFolder);
        }
        File file = new File(dataFolder, FILE_NAME);
        if (!file.isFile()) {
            plugin.saveResource(FILE_NAME, false);
            log.info("Created default " + FILE_NAME + ".");
        }

        YamlConfiguration bundled = loadBundled();
        YamlConfiguration user = newYaml();
        try {
            user.load(file);
        } catch (InvalidConfigurationException e) {
            throw new ConfigException(FILE_NAME + " has a YAML syntax error: " + oneLine(e.getMessage()), e);
        } catch (IOException e) {
            throw new ConfigException("Could not read " + FILE_NAME + ": " + e.getMessage(), e);
        }

        upgrade(file, user, bundled, log);

        Set<String> warnings = new LinkedHashSet<>();
        PluginSettings settings = new SettingsParser(new BukkitConfigLookups(), warnings::add)
                .parse(ConfigTrees.toMap(user), ConfigTrees.toMap(bundled));
        for (String w : warnings) {
            log.warning(FILE_NAME + " → " + w);
        }
        log.info(summary(settings) + (warnings.isEmpty() ? "" : " (" + warnings.size() + " warning(s), see above)"));
        return settings;
    }

    /** Migrates and back-fills missing keys; rewrites the file (after a backup) when anything changed. */
    private void upgrade(File file, YamlConfiguration user, YamlConfiguration bundled, Logger log)
            throws ConfigException {
        boolean hadVersion = user.contains("config-version");
        ConfigMigrator.Result result;
        try {
            result = new ConfigMigrator().migrate(user, log::info);
        } catch (RuntimeException e) {
            throw new ConfigException("Could not migrate " + FILE_NAME + ": " + e.getMessage(), e);
        }
        if (result.tooNew()) {
            log.warning(FILE_NAME + " is config-version " + result.fromVersion() + " but this PrimedRTP build only knows "
                    + "version " + ConfigMigrator.CURRENT_VERSION + "; it was not modified. Unknown options are ignored.");
            return;
        }
        List<String> added = ConfigMigrator.fillMissing(user, bundled, ConfigMigrator.CONFIG_SKIP);
        added.remove("config-version"); // migrate() already set it when missing
        if (!result.changed() && added.isEmpty() && hadVersion) {
            return;
        }
        Path backup = backup(file.toPath(), log);
        try {
            user.save(file);
        } catch (IOException e) {
            log.warning("Could not update " + FILE_NAME + " (" + e.getMessage() + "); continuing with the in-memory "
                    + "version.");
            return;
        }
        if (!added.isEmpty()) {
            List<String> shown = added.size() > 8 ? new ArrayList<>(added.subList(0, 8)) : added;
            log.info("Added " + added.size() + " new option(s) to " + FILE_NAME + ": " + String.join(", ", shown)
                    + (added.size() > shown.size() ? ", ..." : "")
                    + (backup != null ? " (backup: " + backup.getFileName() + ")" : ""));
        }
    }

    /** Copies config.yml to the first free config.yml.bak-N; returns null if the copy failed. */
    private static Path backup(Path file, Logger log) {
        try {
            for (int n = 1; n < 10_000; n++) {
                Path target = file.resolveSibling(FILE_NAME + ".bak-" + n);
                if (!Files.exists(target)) {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    return target;
                }
            }
            log.warning("Too many " + FILE_NAME + " backups; not creating another.");
        } catch (IOException e) {
            log.warning("Could not back up " + FILE_NAME + ": " + e.getMessage());
        }
        return null;
    }

    private YamlConfiguration loadBundled() throws ConfigException {
        try (InputStream in = plugin.getResource(FILE_NAME)) {
            if (in == null) {
                throw new ConfigException("Bundled " + FILE_NAME + " is missing from the plugin jar");
            }
            YamlConfiguration yaml = newYaml();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            throw new ConfigException("Bundled " + FILE_NAME + " is unreadable: " + e.getMessage(), e);
        }
    }

    static YamlConfiguration newYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.options().width(120);
        return yaml;
    }

    static String summary(PluginSettings settings) {
        Map<String, WorldSettings> worlds = settings.worlds();
        List<String> names = new ArrayList<>();
        worlds.forEach((name, ws) -> names.add(ws.enabled() ? name : name + " (disabled)"));
        return "Loaded " + worlds.size() + " world profile" + (worlds.size() == 1 ? "" : "s")
                + (names.isEmpty() ? "" : ": " + String.join(", ", names))
                + (settings.enableUnlistedWorlds() ? "; other worlds use worlds.defaults" : "");
    }

    private static String oneLine(String s) {
        return s == null ? "unknown error" : s.replaceAll("\\s+", " ").trim();
    }
}

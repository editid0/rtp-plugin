package io.github.editid0.swiftrtp.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Upgrades config.yml between schema versions and back-fills options added in newer plugin versions.
 * <p>
 * Each {@link Step} upgrades exactly one version (N → N+1) by editing the loaded {@link YamlConfiguration} in place
 * (renaming/moving/re-typing keys). After all steps ran, {@link #fillMissing} copies every key that exists in the
 * bundled default config but not in the user's file (with its comments), so the file always documents every option.
 * <p>
 * To add a migration: bump {@link #CURRENT_VERSION}, bump {@code config-version} in the bundled config.yml, and add a
 * step with {@code from() == CURRENT_VERSION - 1} to {@link #STEPS}.
 */
public final class ConfigMigrator {

    /** Schema version written by this plugin build (matches {@code config-version} in the bundled config.yml). */
    public static final int CURRENT_VERSION = 1;

    /** One upgrade step. */
    public interface Step {
        /** Version this step upgrades from (it produces {@code from() + 1}). */
        int from();

        /** Short human-readable summary, logged when applied. */
        String description();

        void apply(YamlConfiguration config);
    }

    /** All known steps. Empty while the schema is at version 1. */
    private static final List<Step> STEPS = List.of();

    /** Outcome of {@link #migrate}. */
    public record Result(int fromVersion, int toVersion, List<String> applied, boolean tooNew) {
        public boolean changed() {
            return fromVersion != toVersion;
        }
    }

    private final List<Step> steps;
    private final int targetVersion;

    public ConfigMigrator() {
        this(STEPS, CURRENT_VERSION);
    }

    /** For tests / future use: a custom step list and target version. */
    public ConfigMigrator(List<Step> steps, int targetVersion) {
        this.steps = List.copyOf(steps);
        this.targetVersion = targetVersion;
    }

    /**
     * Runs every step needed to bring {@code config} to the target version and updates {@code config-version}.
     * A missing {@code config-version} is treated as version 1 (the first schema). A version newer than this build
     * is left untouched ({@link Result#tooNew()}).
     *
     * @throws IllegalStateException if a required step is missing (a plugin bug)
     */
    public Result migrate(YamlConfiguration config, Consumer<String> log) {
        int version = config.contains("config-version") ? config.getInt("config-version", 1) : 1;
        int from = version;
        if (version > targetVersion) {
            return new Result(from, from, List.of(), true);
        }
        List<String> applied = new ArrayList<>();
        while (version < targetVersion) {
            final int v = version;
            Step step = steps.stream().filter(s -> s.from() == v).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No config migration from version " + v));
            step.apply(config);
            version++;
            config.set("config-version", version);
            applied.add("v" + v + " -> v" + version + ": " + step.description());
            log.accept("Migrated config.yml " + applied.getLast());
        }
        if (!config.contains("config-version")) {
            config.set("config-version", version);
        }
        return new Result(from, version, applied, false);
    }

    /**
     * Copies keys present in {@code defaults} but missing from {@code user} (values and comments). Paths matching
     * {@code skip} are neither copied nor descended into (e.g. example worlds the user deleted).
     *
     * @return the dotted paths that were added (a missing section counts once)
     */
    public static List<String> fillMissing(YamlConfiguration user, YamlConfiguration defaults, Predicate<String> skip) {
        Map<String, Object> defTree = ConfigTrees.toMap(defaults);
        Map<String, Object> userTree = ConfigTrees.toMap(user);
        List<String> added = new ArrayList<>();
        ConfigTrees.findMissing(defTree, userTree, skip, (path, value) -> added.add(path));
        for (String path : added) {
            copy(defaults, user, path);
        }
        return added;
    }

    /** Default skip rule for config.yml: user-data maps and example worlds are never re-added. */
    public static final Predicate<String> CONFIG_SKIP = p ->
            p.startsWith("world-redirects.")
                    || p.startsWith("worlds.defaults.cooldown.tiers.")
                    || (p.startsWith("worlds.") && !p.equals("worlds.defaults") && !p.startsWith("worlds.defaults."));

    private static void copy(YamlConfiguration from, YamlConfiguration to, String path) {
        if (from.isConfigurationSection(path)) {
            ConfigurationSection section = from.getConfigurationSection(path);
            to.createSection(path);
            copyComments(from, to, path);
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    copy(from, to, path + "." + key);
                }
            }
        } else {
            to.set(path, from.get(path));
            copyComments(from, to, path);
        }
    }

    private static void copyComments(YamlConfiguration from, YamlConfiguration to, String path) {
        List<String> comments = from.getComments(path);
        if (!comments.isEmpty()) {
            to.setComments(path, comments);
        }
        List<String> inline = from.getInlineComments(path);
        if (!inline.isEmpty()) {
            to.setInlineComments(path, inline);
        }
    }
}

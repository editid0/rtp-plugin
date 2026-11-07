package io.github.editid0.swiftrtp.config;

import io.github.editid0.swiftrtp.config.PluginSettings.FirstJoinTrigger;
import io.github.editid0.swiftrtp.config.PluginSettings.PerformanceSettings;
import io.github.editid0.swiftrtp.config.PluginSettings.RespawnTrigger;
import io.github.editid0.swiftrtp.config.PluginSettings.SignTrigger;
import io.github.editid0.swiftrtp.config.PluginSettings.StorageSettings;
import io.github.editid0.swiftrtp.config.PluginSettings.TriggerSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.AreaSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.BiomeFilter;
import io.github.editid0.swiftrtp.config.WorldSettings.CenterMode;
import io.github.editid0.swiftrtp.config.WorldSettings.ChunkPreference;
import io.github.editid0.swiftrtp.config.WorldSettings.CooldownSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.CostSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.EffectSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.FilterMode;
import io.github.editid0.swiftrtp.config.WorldSettings.ItemCost;
import io.github.editid0.swiftrtp.config.WorldSettings.ParticleSpec;
import io.github.editid0.swiftrtp.config.WorldSettings.PoolSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.PotionSpec;
import io.github.editid0.swiftrtp.config.WorldSettings.SearchMode;
import io.github.editid0.swiftrtp.config.WorldSettings.SearchSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.Shape;
import io.github.editid0.swiftrtp.config.WorldSettings.SoundSpec;
import io.github.editid0.swiftrtp.config.WorldSettings.TeleportSettings;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Turns configuration trees (plain maps, see {@link ConfigTrees}) into immutable {@link PluginSettings}. Pure apart
 * from the injected {@link ConfigLookups}; all problems are reported through the warning sink and never thrown.
 * <p>
 * Layering, lowest to highest priority:
 * <ol>
 *   <li>the bundled default config.yml ({@code bundled}) — guarantees every key has a value;</li>
 *   <li>the user's {@code worlds.defaults} — the template for every world;</li>
 *   <li>the user's {@code worlds.<name>} — per-world partial overrides.</li>
 * </ol>
 * Maps merge recursively; scalars and lists replace; {@link #WORLD_OPAQUE} maps replace wholesale.
 */
public final class SettingsParser {

    /** Name given to the template {@link WorldSettings} ({@link PluginSettings#defaults()}). */
    public static final String DEFAULTS_NAME = "*";

    /** Paths (relative to a world section) whose maps are user data and replace instead of merging. */
    public static final Predicate<String> WORLD_OPAQUE = p -> p.equals("cooldown.tiers");

    /** Paths (relative to the config root) whose maps are user data and replace instead of merging. */
    public static final Predicate<String> ROOT_OPAQUE = p -> p.equals("world-redirects") || p.equals("worlds")
            || p.equals("worlds.defaults.cooldown.tiers");

    private final ConfigLookups lookups;
    private final Consumer<String> warn;

    public SettingsParser(ConfigLookups lookups, Consumer<String> warn) {
        this.lookups = lookups;
        this.warn = warn;
    }

    /**
     * @param user    the user's config.yml as a plain tree
     * @param bundled the bundled default config.yml as a plain tree
     */
    public PluginSettings parse(Map<String, Object> user, Map<String, Object> bundled) {
        // Typo detection against the bundled schema (worlds are checked individually below).
        ConfigTrees.findUnknown(bundled, user, ROOT_OPAQUE, "", p -> warn.accept(p + ": unknown key (typo?); ignored"));

        Map<String, Object> merged = ConfigTrees.merge(bundled, user, ROOT_OPAQUE);
        ConfigNode root = new ConfigNode("", merged, warn);

        int version = root.integer("config-version", ConfigMigrator.CURRENT_VERSION, 0, Integer.MAX_VALUE);
        boolean debug = root.bool("debug", false);
        String defaultWorld = root.optString("default-world");

        Map<String, String> redirects = parseRedirects(root);
        List<String> aliases = parseAliases(root);
        PerformanceSettings performance = parsePerformance(root.child("performance"));
        StorageSettings storage = parseStorage(root.child("storage"));
        TriggerSettings triggers = parseTriggers(root.child("triggers"));
        boolean enableUnlisted = root.bool("enable-unlisted-worlds", false);

        // ---- worlds -----------------------------------------------------------------------------------------
        Map<String, Object> bundledDefaults = mapAt(bundled, "worlds.defaults");
        Map<String, Object> userWorlds = mapAt(user, "worlds");
        Map<String, Object> userDefaults = mapAt(userWorlds, "defaults");
        ConfigTrees.findUnknown(bundledDefaults, userDefaults, WORLD_OPAQUE, "worlds.defaults",
                p -> warn.accept(p + ": unknown key (typo?); ignored"));
        Map<String, Object> template = ConfigTrees.merge(bundledDefaults, userDefaults, WORLD_OPAQUE);
        WorldSettings defaults = parseWorld(DEFAULTS_NAME, new ConfigNode("worlds.defaults", template, warn));

        Map<String, WorldSettings> worlds = new LinkedHashMap<>();
        if (user.get("worlds") == null) {
            // No worlds section at all: fall back to the bundled example worlds.
            userWorlds = mapAt(bundled, "worlds");
        }
        for (Map.Entry<String, Object> e : userWorlds.entrySet()) {
            String name = e.getKey();
            if (name.equals("defaults")) {
                continue;
            }
            String path = "worlds." + name;
            Map<String, Object> override;
            if (e.getValue() == null) {
                override = Map.of(); // "world_x:" with no body = all defaults
            } else if (e.getValue() instanceof Map<?, ?> m) {
                override = ConfigTrees.asMap(m);
            } else {
                warn.accept(path + ": expected a section; world skipped");
                continue;
            }
            ConfigTrees.findUnknown(bundledDefaults, override, WORLD_OPAQUE, path,
                    p -> warn.accept(p + ": unknown key (typo?); ignored"));
            Map<String, Object> worldTree = ConfigTrees.merge(template, override, WORLD_OPAQUE);
            // Only report problems for values this world actually sets; inherited ones were reported for defaults.
            Consumer<String> worldWarn = onlyOverridden(path, override);
            worlds.put(name, parseWorld(name, new ConfigNode(path, worldTree, worldWarn)));
        }

        if (defaultWorld != null && !worlds.containsKey(defaultWorld) && !enableUnlisted) {
            warn.accept("default-world: '" + defaultWorld + "' has no profile under 'worlds' and "
                    + "enable-unlisted-worlds is false, so it is not RTP-enabled");
        }
        redirects.forEach((from, to) -> {
            if (!worlds.containsKey(to) && !enableUnlisted) {
                warn.accept("world-redirects." + from + ": target '" + to + "' is not RTP-enabled");
            }
        });

        return new PluginSettings(version, debug, defaultWorld, Collections.unmodifiableMap(redirects),
                List.copyOf(aliases), performance, storage, triggers, defaults, enableUnlisted,
                Collections.unmodifiableMap(worlds));
    }

    // =========================================================================================================
    // global sections
    // =========================================================================================================

    private Map<String, String> parseRedirects(ConfigNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        Object raw = root.raw("world-redirects");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            root.warn("world-redirects", "expected a section of 'from: to' pairs; ignored");
            return out;
        }
        m.forEach((k, v) -> {
            String from = String.valueOf(k);
            String to = v == null ? "" : v.toString().trim();
            if (to.isEmpty() || v instanceof Map<?, ?> || v instanceof List<?>) {
                warn.accept("world-redirects." + from + ": expected a world name; ignored");
            } else if (to.equals(from)) {
                warn.accept("world-redirects." + from + ": redirects to itself; ignored");
            } else {
                out.put(from, to);
            }
        });
        return out;
    }

    private List<String> parseAliases(ConfigNode root) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : root.stringList("command-aliases")) {
            String alias = EntryLists.normalizeAlias(raw);
            if (alias == null) {
                root.warn("command-aliases", "'" + raw + "' is not a valid command name; skipped");
            } else if (alias.equals("rtp") || alias.equals("swiftrtp")) {
                // already registered
            } else {
                out.add(alias);
            }
        }
        return new ArrayList<>(out);
    }

    PerformanceSettings parsePerformance(ConfigNode n) {
        Duration refill = n.duration("refill-check-interval", Duration.ofSeconds(5));
        if (refill.compareTo(Duration.ofMillis(250)) < 0) {
            n.warn("refill-check-interval", "must be at least 250ms; using 250ms");
            refill = Duration.ofMillis(250);
        }
        return new PerformanceSettings(
                n.integer("max-concurrent-chunk-loads", 4, 1, 256),
                n.integer("max-chunk-loads-per-second", 20, 1, 10_000),
                n.decimal("pause-above-mspt", 45.0, 0, 10_000),
                n.bool("pause-when-empty", false),
                n.duration("startup-delay", Duration.ofSeconds(10)),
                refill,
                n.integer("max-searches-per-world", 2, 1, 64));
    }

    StorageSettings parseStorage(ConfigNode n) {
        return new StorageSettings(
                n.bool("persist-pools", true),
                n.bool("persist-cooldowns", true),
                n.duration("autosave-interval", Duration.ofMinutes(5)));
    }

    TriggerSettings parseTriggers(ConfigNode n) {
        ConfigNode fj = n.child("first-join");
        ConfigNode rs = n.child("respawn");
        ConfigNode sg = n.child("signs");
        String header = sg.string("header", "[RTP]").trim();
        if (header.isEmpty()) {
            sg.warn("header", "must not be empty; using [RTP]");
            header = "[RTP]";
        }
        String createPerm = sg.optString("create-permission");
        return new TriggerSettings(
                new FirstJoinTrigger(fj.bool("enabled", false), fj.optString("world"),
                        fj.duration("delay", Duration.ofSeconds(1))),
                new RespawnTrigger(rs.bool("enabled", false), rs.optString("world"),
                        rs.bool("only-without-bed", true)),
                new SignTrigger(sg.bool("enabled", true), header,
                        createPerm == null ? "swiftrtp.sign.create" : createPerm));
    }

    // =========================================================================================================
    // world sections
    // =========================================================================================================

    WorldSettings parseWorld(String name, ConfigNode n) {
        String permission = n.optString("permission");
        if (permission != null && !name.equals(DEFAULTS_NAME)) {
            // The template keeps the literal {world} so it can be substituted for unlisted worlds.
            permission = permission.replace("{world}", name);
        }
        return new WorldSettings(name,
                n.bool("enabled", true),
                permission,
                parseArea(n.child("area")),
                parseSearch(n.child("search")),
                parsePool(n.child("pool")),
                parseTeleport(n.child("teleport")),
                parseCooldown(n.child("cooldown")),
                parseCost(n.child("cost")));
    }

    AreaSettings parseArea(ConfigNode n) {
        ConfigNode c = n.child("center");
        CenterMode centerMode = c.enumValue("mode", CenterMode.class, CenterMode.WORLD_SPAWN);
        int cx = c.integer("x", 0, -30_000_000, 30_000_000);
        int cz = c.integer("z", 0, -30_000_000, 30_000_000);
        int min = n.integer("min-radius", 0, 0, 30_000_000);
        int max = n.integer("max-radius", 5000, 1, 30_000_000);
        if (min > max) {
            warn.accept(n.path() + ": min-radius (" + min + ") is greater than max-radius (" + max + "); swapped");
            int t = min;
            min = max;
            max = t;
        }
        return new AreaSettings(centerMode, cx, cz,
                n.enumValue("shape", Shape.class, Shape.CIRCLE),
                min, max,
                n.bool("respect-world-border", true),
                n.integer("border-padding", 16, 0, 10_000));
    }

    SearchSettings parseSearch(ConfigNode n) {
        Integer minY = n.optInteger("min-y", null, -2048, 4096);
        Integer maxY = n.optInteger("max-y", null, -2048, 4096);
        if (minY != null && maxY != null && minY > maxY) {
            warn.accept(n.path() + ": min-y (" + minY + ") is greater than max-y (" + maxY + "); swapped");
            Integer t = minY;
            minY = maxY;
            maxY = t;
        }
        return new SearchSettings(
                n.enumValue("mode", SearchMode.class, SearchMode.AUTO),
                minY, maxY,
                n.integer("max-attempts", 32, 1, 1000),
                n.bool("require-sky-access", true),
                n.bool("avoid-liquids", true),
                blocks(n, "unsafe-floor"),
                blocks(n, "unsafe-nearby"),
                n.enumValue("chunk-preference", ChunkPreference.class, ChunkPreference.ANY),
                parseBiomes(n.child("biomes")),
                n.bool("avoid-claims", true),
                n.integer("min-distance-from-players", 0, 0, 1_000_000));
    }

    private Set<Material> blocks(ConfigNode n, String key) {
        Set<Material> set = EntryLists.resolve(n.stringList(key), n.path(key), n.warnSink(),
                lookups::block, lookups::blockTag);
        return Collections.unmodifiableSet(set);
    }

    BiomeFilter parseBiomes(ConfigNode n) {
        FilterMode mode = n.enumValue("mode", FilterMode.class, FilterMode.BLACKLIST);
        Set<String> biomes = new LinkedHashSet<>();
        for (String raw : n.stringList("list")) {
            String entry = EntryLists.normalizeBiome(raw);
            if (entry == null) {
                continue;
            }
            if (!EntryLists.isGlob(entry)) {
                if (!EntryLists.isValidKey(entry)) {
                    n.warn("list", "'" + raw + "' is not a valid biome key; skipped");
                    continue;
                }
                if (!lookups.exists(ConfigLookups.Kind.BIOME, entry)) {
                    n.warn("list", "unknown biome '" + raw + "'; skipped");
                    continue;
                }
            }
            biomes.add(entry);
        }
        if (mode == FilterMode.WHITELIST && biomes.isEmpty()) {
            n.warn("list", "whitelist is empty, so every biome is allowed");
        }
        return new BiomeFilter(mode, Collections.unmodifiableSet(biomes));
    }

    PoolSettings parsePool(ConfigNode n) {
        int keep = n.integer("keep-loaded-radius", 1, 0, 8);
        int pregen = n.integer("pregen-radius", 3, 0, 16);
        if (pregen < keep) {
            n.warn("pregen-radius", pregen + " is smaller than keep-loaded-radius (" + keep + "); using " + keep);
            pregen = keep;
        }
        return new PoolSettings(
                n.bool("enabled", true),
                n.integer("size", 10, 0, 500),
                keep, pregen,
                n.duration("expire-after", Duration.ofMinutes(30)),
                n.integer("min-spacing", 256, 0, 1_000_000));
    }

    TeleportSettings parseTeleport(ConfigNode n) {
        List<String> commands = new ArrayList<>();
        for (String raw : n.stringList("commands")) {
            String cmd = raw.trim();
            if (cmd.isEmpty()) {
                continue;
            }
            String lower = cmd.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("console:") || lower.startsWith("player:") || lower.startsWith("op:"))) {
                n.warn("commands", "'" + raw + "' has no 'console:', 'player:' or 'op:' prefix; running it as console");
                cmd = "console: " + cmd;
            }
            commands.add(cmd);
        }
        return new TeleportSettings(
                n.duration("warmup", Duration.ofSeconds(3)),
                n.bool("cancel-on-move", true),
                n.decimal("move-tolerance", 0.5, 0, 64),
                n.bool("cancel-on-damage", true),
                n.duration("invulnerability", Duration.ofSeconds(3)),
                n.duration("fall-damage-protection", Duration.ofSeconds(10)),
                parseEffects(n.child("effects")),
                List.copyOf(commands),
                n.integer("max-retries", 3, 0, 20));
    }

    EffectSettings parseEffects(ConfigNode n) {
        List<PotionSpec> potions = new ArrayList<>();
        for (Object o : n.list("potion-effects")) {
            PotionSpec p = potion(n, "potion-effects", o);
            if (p != null) {
                potions.add(p);
            }
        }
        return new EffectSettings(
                n.bool("arrival-title", true),
                n.bool("action-bar-countdown", true),
                n.bool("boss-bar-countdown", false),
                sound(n, "warmup-tick-sound"),
                sound(n, "teleport-sound"),
                particle(n, "warmup-particles"),
                particle(n, "arrival-particles"),
                List.copyOf(potions));
    }

    /** Returns a validated, normalised registry key, or null (with warning) if invalid/unknown. */
    private @Nullable String registryKey(ConfigNode n, String key, @Nullable Object raw, ConfigLookups.Kind kind) {
        if (raw == null || raw instanceof Map<?, ?> || raw instanceof List<?>) {
            n.warn(key, "missing " + kind.name().toLowerCase(Locale.ROOT) + " key; disabled");
            return null;
        }
        String k = EntryLists.normalizeKey(raw.toString());
        if (!EntryLists.isValidKey(k)) {
            n.warn(key, "'" + raw + "' is not a valid key; disabled");
            return null;
        }
        if (!lookups.exists(kind, k)) {
            n.warn(key, "unknown " + kind.name().toLowerCase(Locale.ROOT) + " '" + k + "'; disabled");
            return null;
        }
        return k;
    }

    private static boolean isOff(@Nullable Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return true;
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return false;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("none") || s.equals("off") || s.equals("false");
    }

    @Nullable SoundSpec sound(ConfigNode parent, String key) {
        Object v = parent.raw(key);
        if (isOff(v)) {
            return null;
        }
        if (v instanceof Map<?, ?>) {
            ConfigNode s = parent.child(key);
            if (!s.bool("enabled", true)) {
                return null;
            }
            String k = registryKey(s, "sound", s.raw("sound"), ConfigLookups.Kind.SOUND);
            return k == null ? null : new SoundSpec(k,
                    (float) s.decimal("volume", 1.0, 0, 10),
                    (float) s.decimal("pitch", 1.0, 0.5, 2.0));
        }
        String k = registryKey(parent, key, v, ConfigLookups.Kind.SOUND);
        return k == null ? null : new SoundSpec(k, 1f, 1f);
    }

    @Nullable ParticleSpec particle(ConfigNode parent, String key) {
        Object v = parent.raw(key);
        if (isOff(v)) {
            return null;
        }
        if (v instanceof Map<?, ?>) {
            ConfigNode s = parent.child(key);
            if (!s.bool("enabled", true)) {
                return null;
            }
            String k = registryKey(s, "particle", s.raw("particle"), ConfigLookups.Kind.PARTICLE);
            return k == null ? null : new ParticleSpec(k,
                    s.integer("count", 20, 0, 1000),
                    s.decimal("spread", 0.5, 0, 16));
        }
        String k = registryKey(parent, key, v, ConfigLookups.Kind.PARTICLE);
        return k == null ? null : new ParticleSpec(k, 20, 0.5);
    }

    private @Nullable PotionSpec potion(ConfigNode parent, String key, Object v) {
        if (v instanceof Map<?, ?> m) {
            ConfigNode s = new ConfigNode(parent.path(key), ConfigTrees.asMap(m), parent.warnSink());
            String k = registryKey(s, "effect", s.raw("effect"), ConfigLookups.Kind.EFFECT);
            if (k == null) {
                return null;
            }
            return new PotionSpec(k,
                    s.duration("duration", Duration.ofSeconds(10)),
                    s.integer("amplifier", 0, 0, 255),
                    s.bool("ambient", false),
                    s.bool("particles", true),
                    s.bool("icon", true));
        }
        String k = registryKey(parent, key, v, ConfigLookups.Kind.EFFECT);
        return k == null ? null : new PotionSpec(k, Duration.ofSeconds(10), 0, false, true, true);
    }

    CooldownSettings parseCooldown(ConfigNode n) {
        Map<String, Duration> tiers = new LinkedHashMap<>();
        Object raw = n.raw("tiers");
        if (raw instanceof Map<?, ?> m) {
            ConfigNode t = n.child("tiers");
            for (Object k : m.keySet()) {
                String tier = String.valueOf(k).trim().toLowerCase(Locale.ROOT);
                if (!tier.matches("[a-z0-9_.\\-]+")) {
                    t.warn(String.valueOf(k), "invalid tier name (use letters, digits, '_', '-', '.'); skipped");
                    continue;
                }
                if (m.get(k) == null) {
                    t.warn(String.valueOf(k), "missing duration; skipped");
                    continue;
                }
                tiers.put(tier, t.duration(String.valueOf(k), Duration.ZERO));
            }
        } else if (raw != null && !isOff(raw)) {
            n.warn("tiers", "expected a section of 'tier: duration' pairs; ignored");
        }
        return new CooldownSettings(
                n.duration("base", Duration.ofMinutes(5)),
                Collections.unmodifiableMap(tiers),
                n.bool("per-world", false));
    }

    CostSettings parseCost(ConfigNode n) {
        Map<Material, Integer> items = new LinkedHashMap<>();
        for (Object o : n.list("items")) {
            String materialName;
            int amount;
            if (o instanceof Map<?, ?> m) {
                ConfigNode s = new ConfigNode(n.path("items"), ConfigTrees.asMap(m), n.warnSink());
                Object mat = m.get("material") != null ? m.get("material") : m.get("item");
                if (mat == null) {
                    n.warn("items", "entry " + o + " has no 'material'; skipped");
                    continue;
                }
                materialName = EntryLists.normalizeKey(mat.toString());
                amount = s.integer("amount", 1, 1, 100_000);
            } else {
                EntryLists.ItemEntry entry = EntryLists.parseItem(o.toString());
                if (entry == null) {
                    n.warn("items", "'" + o + "' is not 'material' or 'material:amount'; skipped");
                    continue;
                }
                materialName = entry.materialKey();
                amount = entry.amount();
            }
            Material material = lookups.item(materialName);
            if (material == null) {
                n.warn("items", "unknown item '" + materialName + "'; skipped");
                continue;
            }
            items.merge(material, amount, Integer::sum); // same item listed twice → amounts add up
        }
        List<ItemCost> costs = new ArrayList<>();
        items.forEach((m, a) -> costs.add(new ItemCost(m, a)));
        return new CostSettings(
                n.decimal("money", 0, 0, 1e15),
                n.integer("xp-levels", 0, 0, 100_000),
                List.copyOf(costs),
                n.bool("refund-on-failure", true));
    }

    // =========================================================================================================
    // helpers
    // =========================================================================================================

    /**
     * Wraps the warning sink so a world only reports problems located in a section it overrides (inherited values
     * were already reported under {@code worlds.defaults}).
     */
    private Consumer<String> onlyOverridden(String worldPath, Map<String, Object> override) {
        String prefix = worldPath + ".";
        return msg -> {
            int sep = msg.indexOf(": ");
            String p = sep < 0 ? msg : msg.substring(0, sep);
            if (!p.startsWith(prefix)) {
                warn.accept(msg);
                return;
            }
            String rel = p.substring(prefix.length());
            // Walk up until we find a path the override sets (a parent section counts too, e.g. "area" for
            // cross-field checks); report only if it is set by this world.
            if (ConfigTrees.get(override, rel) != null) {
                warn.accept(msg);
            }
        };
    }

    private static Map<String, Object> mapAt(Map<String, Object> root, String path) {
        Object v = ConfigTrees.get(root, path);
        return v instanceof Map<?, ?> m ? ConfigTrees.asMap(m) : Map.of();
    }
}

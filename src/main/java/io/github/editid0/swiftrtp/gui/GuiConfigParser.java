package io.github.editid0.swiftrtp.gui;

import io.github.editid0.swiftrtp.config.DurationParser;
import io.github.editid0.swiftrtp.config.WorldSettings.SoundSpec;
import io.github.editid0.swiftrtp.gui.GuiConfig.ItemTemplate;
import io.github.editid0.swiftrtp.gui.GuiConfig.Sounds;
import io.github.editid0.swiftrtp.gui.GuiConfig.StaticButton;
import io.github.editid0.swiftrtp.gui.GuiConfig.Texts;
import io.github.editid0.swiftrtp.gui.GuiConfig.WorldButton;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Parses gui.yml into a {@link GuiConfig}. Pure (no server access).
 * <p>
 * Merge rules: every scalar/list key missing from the user file falls back to the bundled default file. The
 * {@code items} and {@code buttons} maps are taken as a whole from the user file when present (so deleting a world
 * entry really removes it); inside each entry, missing keys use built-in fallbacks, and the {@code cooldown} /
 * {@code locked} variants inherit every key they do not override from the entry itself.
 * <p>
 * Problems never throw; they are reported through {@code warn} and a sensible fallback is used.
 */
public final class GuiConfigParser {

    private static final ItemTemplate FALLBACK_ITEM =
            new ItemTemplate("PAPER", "", List.of(), false, null, null, false, 1);

    private final ConfigurationSection user;
    private final ConfigurationSection defaults;
    private final Consumer<String> warn;

    private GuiConfigParser(ConfigurationSection user, @Nullable ConfigurationSection defaults, Consumer<String> warn) {
        this.user = user;
        this.defaults = defaults == null ? new MemoryConfiguration() : defaults;
        this.warn = warn;
    }

    public static GuiConfig parse(ConfigurationSection user, @Nullable ConfigurationSection defaults, Consumer<String> warn) {
        return new GuiConfigParser(user, defaults, warn).parse();
    }

    private GuiConfig parse() {
        boolean enabled = bool("enabled", true);
        boolean bare = bool("open-on-bare-command", false);
        String title = string("title", "Random Teleport");

        int rawRows = integer("rows", 3);
        int rows = SlotLayout.clampRows(rawRows);
        if (rows != rawRows) {
            warn.accept("gui.yml: rows must be 1-6 (got " + rawRows + "), using " + rows);
        }

        Duration refresh = duration("refresh-interval", Duration.ofSeconds(1));

        ItemTemplate filler = null;
        ConfigurationSection fillerSec = section("filler");
        if (fillerSec != null && fillerSec.getBoolean("enabled", true)) {
            filler = item(fillerSec, new ItemTemplate("GRAY_STAINED_GLASS_PANE", " ", List.of(), false, null, null,
                    false, 1), "filler");
        }

        Texts texts = new Texts(
                string("text.status-ready", "<green>Ready"),
                string("text.status-cooldown", "<gold>On cooldown"),
                string("text.status-locked", "<red>Locked"),
                string("text.cooldown-ready", "<green>Ready"),
                string("text.cost-free", "<green>Free"),
                string("text.no-cooldown", "<green>None"),
                Math.max(1, integer("text.pool-bar.length", 10)),
                string("text.pool-bar.symbol", "|"),
                colorTag(string("text.pool-bar.filled-color", "green")),
                colorTag(string("text.pool-bar.empty-color", "dark_gray")));

        Sounds sounds = new Sounds(sound("sounds.open"), sound("sounds.click"), sound("sounds.denied"));

        Set<Integer> taken = new HashSet<>();
        List<StaticButton> buttons = buttons(rows, taken);
        List<WorldButton> worlds = worlds(rows, taken);

        return new GuiConfig(enabled, bare, title, rows, refresh, filler, texts, sounds, List.copyOf(worlds),
                List.copyOf(buttons));
    }

    // ---- sections ----------------------------------------------------------------------------------------------

    private List<StaticButton> buttons(int rows, Set<Integer> taken) {
        ConfigurationSection root = mapSection("buttons");
        List<StaticButton> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                warn.accept("gui.yml: buttons." + id + " must be a section");
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            List<Object> rawSlots = new ArrayList<>();
            if (sec.isList("slots")) {
                rawSlots.addAll(sec.getList("slots", List.of()));
            } else if (sec.isSet("slot")) {
                rawSlots.add(sec.get("slot"));
            }
            for (Object raw : rawSlots) {
                Integer slot = SlotLayout.parse(raw);
                if (slot == null || !SlotLayout.inBounds(slot, rows)) {
                    warn.accept("gui.yml: buttons." + id + " has invalid slot '" + raw + "' for " + rows + " row(s)");
                } else if (!taken.add(slot)) {
                    warn.accept("gui.yml: buttons." + id + " slot " + slot + " is already used, skipping it");
                } else {
                    slots.add(slot);
                }
            }
            if (slots.isEmpty()) {
                warn.accept("gui.yml: buttons." + id + " has no usable slot and is ignored");
                continue;
            }
            List<String> rawActions = new ArrayList<>();
            if (sec.isList("actions")) {
                rawActions.addAll(sec.getStringList("actions"));
            } else if (sec.isSet("action")) {
                rawActions.add(sec.getString("action", ""));
            }
            List<MenuAction> actions = new ArrayList<>();
            for (String a : rawActions) {
                MenuAction action = MenuAction.parse(a);
                if (action == null) {
                    warn.accept("gui.yml: buttons." + id + " has unknown action '" + a
                            + "' (use close, rtp:<world>, command:<cmd> or console:<cmd>)");
                } else {
                    actions.add(action);
                }
            }
            out.add(new StaticButton(id, List.copyOf(slots), item(sec, FALLBACK_ITEM, "buttons." + id), List.copyOf(actions)));
        }
        return out;
    }

    private List<WorldButton> worlds(int rows, Set<Integer> taken) {
        ConfigurationSection root = mapSection("items");
        if (root == null) {
            return List.of();
        }
        // First pass: explicit slots; collect entries needing auto placement.
        Map<String, Integer> slots = new LinkedHashMap<>();
        List<String> auto = new ArrayList<>();
        Map<String, ConfigurationSection> sections = new LinkedHashMap<>();
        for (String world : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(world);
            if (sec == null) {
                warn.accept("gui.yml: items." + world + " must be a section");
                continue;
            }
            sections.put(world, sec);
            Object raw = sec.get("slot");
            Integer slot = SlotLayout.parse(raw);
            if (slot == null) {
                if (raw != null && !"auto".equalsIgnoreCase(raw.toString().strip())) {
                    warn.accept("gui.yml: items." + world + " has invalid slot '" + raw + "', placing automatically");
                }
                auto.add(world);
            } else if (!SlotLayout.inBounds(slot, rows)) {
                warn.accept("gui.yml: items." + world + " slot " + slot + " is outside the " + rows
                        + "-row menu, placing automatically");
                auto.add(world);
            } else if (!taken.add(slot)) {
                warn.accept("gui.yml: items." + world + " slot " + slot + " is already used, placing automatically");
                auto.add(world);
            } else {
                slots.put(world, slot);
            }
        }
        List<Integer> placed = SlotLayout.autoPlace(rows, auto.size(), taken);
        for (int i = 0; i < auto.size(); i++) {
            if (i < placed.size()) {
                slots.put(auto.get(i), placed.get(i));
                taken.add(placed.get(i));
            } else {
                warn.accept("gui.yml: no free slot left for items." + auto.get(i) + ", it is not shown");
            }
        }

        List<WorldButton> out = new ArrayList<>();
        for (Map.Entry<String, ConfigurationSection> e : sections.entrySet()) {
            Integer slot = slots.get(e.getKey());
            if (slot == null) {
                continue;
            }
            String path = "items." + e.getKey();
            ConfigurationSection sec = e.getValue();
            ItemTemplate normal = item(sec, new ItemTemplate("GRASS_BLOCK", "<white><world>", List.of("<status>"),
                    false, null, null, false, 1), path);
            ConfigurationSection lockedSec = sec.getConfigurationSection("locked");
            ItemTemplate locked = lockedSec == null ? normal : item(lockedSec, normal, path + ".locked");
            ConfigurationSection cdSec = sec.getConfigurationSection("cooldown");
            ItemTemplate cooldown = cdSec == null ? locked : item(cdSec, normal, path + ".cooldown");
            out.add(new WorldButton(e.getKey(), slot, normal, cooldown, locked,
                    sec.getBoolean("hide-if-no-permission", false),
                    sec.getBoolean("hide-if-unavailable", true)));
        }
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    /** Parses an item section, inheriting every missing key from {@code parent}. */
    private ItemTemplate item(ConfigurationSection sec, ItemTemplate parent, String path) {
        String material = sec.getString("material", parent.material()).strip();
        String name = sec.getString("name", parent.name());
        List<String> lore = sec.isList("lore") ? List.copyOf(sec.getStringList("lore"))
                : sec.isString("lore") ? List.of(sec.getString("lore", "").split("\n", -1)) : parent.lore();
        boolean glow = sec.getBoolean("glow", parent.glow());

        Float cmd = parent.customModelData();
        if (sec.isSet("custom-model-data")) {
            Object raw = sec.get("custom-model-data");
            if (raw instanceof Number n) {
                cmd = n.floatValue() == 0f ? null : n.floatValue();
            } else if (raw == null || raw.toString().isBlank() || raw.toString().equalsIgnoreCase("none")) {
                cmd = null;
            } else {
                warn.accept("gui.yml: " + path + ".custom-model-data must be a number");
            }
        }
        String model = parent.itemModel();
        if (sec.isSet("item-model")) {
            String raw = sec.getString("item-model", "").strip();
            model = raw.isEmpty() || raw.equalsIgnoreCase("none") ? null : raw;
        }
        boolean hideTooltip = sec.getBoolean("hide-tooltip", parent.hideTooltip());
        int amount = Math.max(1, Math.min(99, sec.getInt("amount", parent.amount())));
        return new ItemTemplate(material, name, lore, glow, cmd, model, hideTooltip, amount);
    }

    private @Nullable SoundSpec sound(String path) {
        Object raw = user.isSet(path) ? user.get(path) : defaults.get(path);
        if (raw == null) {
            return null;
        }
        if (raw instanceof ConfigurationSection sec) {
            String key = sec.getString("sound", "").strip();
            if (key.isEmpty() || key.equalsIgnoreCase("none")) {
                return null;
            }
            return new SoundSpec(key, (float) sec.getDouble("volume", 1.0), (float) sec.getDouble("pitch", 1.0));
        }
        String key = raw.toString().strip();
        if (key.isEmpty() || key.equalsIgnoreCase("none")) {
            return null;
        }
        return new SoundSpec(key, 1f, 1f);
    }

    // ---- value lookups with default fallback ---------------------------------------------------------------------

    /** The user's section for a map key if present (even if empty), else the default's. */
    private @Nullable ConfigurationSection mapSection(String path) {
        if (user.isSet(path)) {
            return user.getConfigurationSection(path);
        }
        return defaults.getConfigurationSection(path);
    }

    private @Nullable ConfigurationSection section(String path) {
        ConfigurationSection sec = user.getConfigurationSection(path);
        if (sec != null) {
            return sec;
        }
        if (user.isSet(path)) {
            // e.g. "filler: false" / "filler: disabled"
            return null;
        }
        return defaults.getConfigurationSection(path);
    }

    private boolean bool(String path, boolean fallback) {
        return user.isSet(path) ? user.getBoolean(path, fallback) : defaults.getBoolean(path, fallback);
    }

    private int integer(String path, int fallback) {
        return user.isSet(path) ? user.getInt(path, fallback) : defaults.getInt(path, fallback);
    }

    private String string(String path, String fallback) {
        String v = user.isSet(path) ? user.getString(path) : defaults.getString(path);
        return v == null ? fallback : v;
    }

    private Duration duration(String path, Duration fallback) {
        Object raw = user.isSet(path) ? user.get(path) : defaults.get(path);
        if (raw == null) {
            return fallback;
        }
        try {
            Duration d = DurationParser.parse(raw);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (IllegalArgumentException e) {
            warn.accept("gui.yml: " + path + " is not a duration (" + e.getMessage() + "), using " + fallback.toMillis() + "ms");
            return fallback;
        }
    }

    /** Accepts {@code green}, {@code <green>} or {@code #22c55e}; returns the bare tag name. */
    private static String colorTag(String raw) {
        String s = raw.strip();
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isEmpty() ? "white" : s;
    }
}

package io.github.editid0.primedrtp.gui;

import io.github.editid0.primedrtp.gui.GuiConfig.WorldButton;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiConfigParserTest {

    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = GuiConfigParserTest.class.getClassLoader().getResourceAsStream("gui.yml")) {
            assertNotNull(in, "gui.yml on classpath");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void bundledDefaultsParseWithoutWarnings() throws Exception {
        List<String> warnings = new ArrayList<>();
        GuiConfig cfg = GuiConfigParser.parse(bundled(), bundled(), warnings::add);
        assertEquals(List.of(), warnings);
        assertTrue(cfg.enabled());
        assertFalse(cfg.openOnBareCommand());
        assertEquals(3, cfg.rows());
        assertEquals(Duration.ofSeconds(1), cfg.refreshInterval());
        assertNotNull(cfg.filler());
        assertTrue(cfg.filler().hideTooltip());
        assertEquals(List.of("world", "world_nether", "world_the_end"), cfg.worlds().stream().map(WorldButton::world).toList());
        assertEquals(List.of(11, 13, 15), cfg.worlds().stream().map(WorldButton::slot).toList());
        assertEquals("GRASS_BLOCK", cfg.worlds().getFirst().normal().material());
        assertNotNull(cfg.sounds().open());
        assertEquals(1, cfg.buttons().size());
        assertEquals(List.of(22), cfg.buttons().getFirst().slots());
        assertEquals(List.of(new MenuAction.Close()), cfg.buttons().getFirst().actions());
    }

    @Test
    void lockedVariantInheritsFromItem() throws Exception {
        GuiConfig cfg = GuiConfigParser.parse(bundled(), null, w -> {});
        WorldButton end = cfg.worlds().get(2);
        assertEquals("GRAY_DYE", end.locked().material());
        assertEquals(end.normal().lore(), end.locked().lore());
        assertTrue(end.normal().glow());
        assertFalse(end.locked().glow());
        // No explicit cooldown variant: the locked variant is used while on cooldown.
        assertEquals(end.locked(), end.cooldown());
    }

    @Test
    void userItemsReplaceDefaultItemsAndScalarsFallBack() throws Exception {
        GuiConfig cfg = GuiConfigParser.parse(yaml("""
                rows: 1
                items:
                  lobby:
                    material: compass
                    cooldown:
                      material: clock
                """), bundled(), w -> {});
        assertEquals(1, cfg.rows());
        assertEquals("<gradient:#38bdf8:#6366f1><bold>Random Teleport</bold></gradient>", cfg.title());
        assertEquals(1, cfg.worlds().size());
        WorldButton lobby = cfg.worlds().getFirst();
        assertEquals(4, lobby.slot()); // auto-placed in the centre
        assertEquals("compass", lobby.normal().material());
        assertEquals("clock", lobby.cooldown().material());
        assertEquals("compass", lobby.locked().material());
        // Default close button at slot 22 does not fit one row.
        assertTrue(cfg.buttons().isEmpty());
    }

    @Test
    void emptyItemsMapMeansNoWorlds() throws Exception {
        GuiConfig cfg = GuiConfigParser.parse(yaml("items: {}\nbuttons: {}\n"), bundled(), w -> {});
        assertTrue(cfg.worlds().isEmpty());
        assertTrue(cfg.buttons().isEmpty());
    }

    @Test
    void fillerCanBeDisabled() throws Exception {
        assertNull(GuiConfigParser.parse(yaml("filler: false"), bundled(), w -> {}).filler());
        assertNull(GuiConfigParser.parse(yaml("filler:\n  enabled: false"), bundled(), w -> {}).filler());
    }

    @Test
    void problemsAreWarnedAndRecovered() throws Exception {
        List<String> warnings = new ArrayList<>();
        GuiConfig cfg = GuiConfigParser.parse(yaml("""
                rows: 9
                refresh-interval: soon
                items:
                  a: { slot: 4 }
                  b: { slot: 4 }
                  c: { slot: 999 }
                  d: { slot: banana, custom-model-data: x }
                buttons:
                  x: { slot: 4, action: explode }
                  y: { slots: [0, 1], actions: [close, "rtp:a"] }
                """), null, warnings::add);
        assertEquals(6, cfg.rows());
        assertEquals(Duration.ofSeconds(1), cfg.refreshInterval());
        assertEquals(4, cfg.worlds().size());
        long distinct = cfg.worlds().stream().map(WorldButton::slot).distinct().count();
        assertEquals(4, distinct);
        assertTrue(cfg.worlds().stream().noneMatch(w -> w.slot() == 0 || w.slot() == 1));
        assertEquals(2, cfg.buttons().size());
        assertEquals(List.of(), cfg.buttons().getFirst().actions());
        assertEquals(List.of(new MenuAction.Close(), new MenuAction.Rtp("a")), cfg.buttons().get(1).actions());
        assertTrue(warnings.size() >= 6, warnings::toString);
    }

    @Test
    void customModelDataAndItemModel() throws Exception {
        GuiConfig cfg = GuiConfigParser.parse(yaml("""
                items:
                  w: { slot: 0, custom-model-data: 7, item-model: "pack:rtp/w", amount: 500 }
                  v: { slot: 1, custom-model-data: 0, item-model: "" }
                """), null, w -> {});
        assertEquals(7f, cfg.worlds().get(0).normal().customModelData());
        assertEquals("pack:rtp/w", cfg.worlds().get(0).normal().itemModel());
        assertEquals(99, cfg.worlds().get(0).normal().amount());
        assertNull(cfg.worlds().get(1).normal().customModelData());
        assertNull(cfg.worlds().get(1).normal().itemModel());
    }

    @Test
    void addMissingKeysSkipsUserMapsAndScalarOverrides() throws Exception {
        YamlConfiguration user = yaml("""
                rows: 4
                filler: false
                sounds:
                  open: ""
                items:
                  lobby: { slot: 1 }
                """);
        assertTrue(RtpMenu.addMissingKeys(user, bundled()));
        assertEquals(4, user.getInt("rows"));
        assertEquals("1s", user.getString("refresh-interval"));
        assertEquals("<green>Free", user.getString("text.cost-free"));
        assertFalse(user.isConfigurationSection("filler"));
        assertEquals("", user.getString("sounds.open"));
        assertTrue(user.isConfigurationSection("sounds.click"));
        assertEquals(List.of("lobby"), List.copyOf(user.getConfigurationSection("items").getKeys(false)));
        assertFalse(user.isSet("buttons"));
        assertFalse(RtpMenu.addMissingKeys(user, bundled()));
    }
}

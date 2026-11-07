package io.github.editid0.primedrtp.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.options().parseComments(true);
        y.loadFromString(text);
        return y;
    }

    private static ConfigMigrator.Step step(int from, Runnable effect, java.util.function.Consumer<YamlConfiguration> edit) {
        return new ConfigMigrator.Step() {
            @Override public int from() { return from; }
            @Override public String description() { return "step " + from; }
            @Override public void apply(YamlConfiguration c) { effect.run(); edit.accept(c); }
        };
    }

    @Test
    void currentVersionIsUnchanged() throws Exception {
        var result = new ConfigMigrator().migrate(yaml("config-version: 1\n"), s -> {});
        assertFalse(result.changed());
        assertFalse(result.tooNew());
    }

    @Test
    void missingVersionIsTreatedAsV1AndWritten() throws Exception {
        YamlConfiguration y = yaml("debug: true\n");
        var result = new ConfigMigrator().migrate(y, s -> {});
        assertFalse(result.changed());
        assertEquals(1, y.getInt("config-version"));
    }

    @Test
    void stepsRunInOrderAndRenameKeys() throws Exception {
        List<Integer> ran = new ArrayList<>();
        var migrator = new ConfigMigrator(List.of(
                step(2, () -> ran.add(2), c -> c.set("c", c.get("b"))),
                step(1, () -> ran.add(1), c -> { c.set("b", c.get("a")); c.set("a", null); })), 3);
        YamlConfiguration y = yaml("config-version: 1\na: 42\n");
        var result = migrator.migrate(y, s -> {});
        assertEquals(List.of(1, 2), ran);
        assertEquals(3, y.getInt("config-version"));
        assertEquals(42, y.getInt("c"));
        assertFalse(y.contains("a"));
        assertEquals(2, result.applied().size());
        assertTrue(result.changed());
    }

    @Test
    void newerVersionIsLeftAlone() throws Exception {
        YamlConfiguration y = yaml("config-version: 99\n");
        var result = new ConfigMigrator().migrate(y, s -> {});
        assertTrue(result.tooNew());
        assertEquals(99, y.getInt("config-version"));
    }

    @Test
    void missingStepIsAnError() throws Exception {
        var migrator = new ConfigMigrator(List.of(), 2);
        assertThrows(IllegalStateException.class, () -> migrator.migrate(yaml("config-version: 1\n"), s -> {}));
    }

    @Test
    void fillMissingAddsKeysWithCommentsAndKeepsUserValues() throws Exception {
        YamlConfiguration defaults = yaml("""
                # the version
                config-version: 1
                # perf section
                performance:
                  # how many
                  max: 4
                  # brand new option
                  added: true
                worlds:
                  defaults:
                    a: 1
                    # new default
                    b: 2
                    cooldown:
                      tiers:
                        vip: 1m
                  world: {}
                """);
        YamlConfiguration user = yaml("""
                # my own comment
                config-version: 1
                performance:
                  max: 8 # mine
                worlds:
                  defaults:
                    a: 5
                    cooldown:
                      tiers:
                        gold: 5s
                """);
        List<String> added = ConfigMigrator.fillMissing(user, defaults, ConfigMigrator.CONFIG_SKIP);
        assertEquals(List.of("performance.added", "worlds.defaults.b"), added);
        assertEquals(8, user.getInt("performance.max"));
        assertEquals(5, user.getInt("worlds.defaults.a"));
        assertTrue(user.getBoolean("performance.added"));
        assertFalse(user.contains("worlds.defaults.cooldown.tiers.vip"));
        assertFalse(user.contains("worlds.world"));

        String out = user.saveToString();
        assertTrue(out.contains("# my own comment"), out);
        assertTrue(out.contains("# mine"), out);
        assertTrue(out.contains("# brand new option"), out);
        assertTrue(out.contains("# new default"), out);
    }

    @Test
    void fillMissingCopiesWholeMissingSections() throws Exception {
        YamlConfiguration defaults = yaml("""
                storage:
                  # persist
                  persist-pools: true
                  nested:
                    deep: 1
                """);
        YamlConfiguration user = yaml("debug: false\n");
        assertEquals(List.of("storage"), ConfigMigrator.fillMissing(user, defaults, p -> false));
        assertEquals(1, user.getInt("storage.nested.deep"));
        assertTrue(user.saveToString().contains("# persist"));
    }

    @Test
    void bundledConfigVersionMatchesCode() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(ConfigMigrator.CURRENT_VERSION, y.getInt("config-version"));
        }
    }
}

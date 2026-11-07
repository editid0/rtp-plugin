package io.github.editid0.swiftrtp.config;

import io.github.editid0.swiftrtp.config.WorldSettings.CenterMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.github.editid0.swiftrtp.config.ConfigTreesTest.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigNodeTest {

    private final List<String> warnings = new ArrayList<>();

    private ConfigNode node(Object... kv) {
        return new ConfigNode("worlds.world.area", m(kv), warnings::add);
    }

    @Test
    void integersClampWithPathInWarning() {
        ConfigNode n = node("max-radius", -5, "size", 900, "ok", "12");
        assertEquals(1, n.integer("max-radius", 5000, 1, 100));
        assertEquals(500, n.integer("size", 10, 0, 500));
        assertEquals(12, n.integer("ok", 0, 0, 100));
        assertEquals(7, n.integer("missing", 7, 0, 100));
        assertEquals(2, warnings.size());
        assertTrue(warnings.getFirst().startsWith("worlds.world.area.max-radius: "));
    }

    @Test
    void badNumberUsesFallback() {
        assertEquals(5, node("x", "lots").integer("x", 5, 0, 10));
        assertEquals(1.5, node("x", "nope").decimal("x", 1.5, 0, 10));
        assertEquals(2, warnings.size());
    }

    @Test
    void optionalInteger() {
        ConfigNode n = node("a", "auto", "b", 120, "c", "");
        assertNull(n.optInteger("a", null, -64, 320));
        assertEquals(120, n.optInteger("b", null, -64, 320));
        assertNull(n.optInteger("c", null, -64, 320));
        assertNull(n.optInteger("d", null, -64, 320));
    }

    @Test
    void enumsAreTolerant() {
        ConfigNode n = node("a", "world-spawn", "b", "World_Border", "c", "FIXED", "d", "nonsense");
        assertEquals(CenterMode.WORLD_SPAWN, n.enumValue("a", CenterMode.class, CenterMode.FIXED));
        assertEquals(CenterMode.WORLD_BORDER, n.enumValue("b", CenterMode.class, CenterMode.FIXED));
        assertEquals(CenterMode.FIXED, n.enumValue("c", CenterMode.class, CenterMode.WORLD_SPAWN));
        assertEquals(CenterMode.WORLD_SPAWN, n.enumValue("d", CenterMode.class, CenterMode.WORLD_SPAWN));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("world_spawn, fixed, world_border"));
    }

    @Test
    void durations() {
        ConfigNode n = node("a", "1h30m", "b", "-5s", "c", "soon", "d", 0);
        assertEquals(Duration.ofMinutes(90), n.duration("a", Duration.ZERO));
        assertEquals(Duration.ZERO, n.duration("b", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(3), n.duration("c", Duration.ofSeconds(3)));
        assertEquals(Duration.ZERO, n.duration("d", Duration.ofSeconds(3)));
        assertEquals(2, warnings.size());
    }

    @Test
    void booleans() {
        ConfigNode n = node("a", true, "b", "yes", "c", "off", "d", "maybe");
        assertTrue(n.bool("a", false));
        assertTrue(n.bool("b", false));
        assertFalse(n.bool("c", true));
        assertTrue(n.bool("d", true));
        assertEquals(1, warnings.size());
    }

    @Test
    void optionalStrings() {
        ConfigNode n = node("a", "", "b", " none ", "c", "x");
        assertNull(n.optString("a"));
        assertNull(n.optString("b"));
        assertNull(n.optString("missing"));
        assertEquals("x", n.optString("c"));
    }

    @Test
    void listsAcceptScalarsAndSkipNulls() {
        ConfigNode n = node("a", "single", "b", new ArrayList<>(java.util.Arrays.asList("x", null, 3)), "c", m("k", 1));
        assertEquals(List.of("single"), n.stringList("a"));
        assertEquals(List.of("x", "3"), n.stringList("b"));
        assertEquals(List.of(), n.stringList("c"));
        assertEquals(1, warnings.size());
    }

    @Test
    void childOfWrongTypeWarns() {
        ConfigNode child = node("center", "spawn").child("center");
        assertEquals("worlds.world.area.center", child.path());
        assertEquals(1, warnings.size());
        assertEquals(5, child.integer("x", 5, 0, 10));
    }
}

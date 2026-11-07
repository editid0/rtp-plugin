package io.github.editid0.swiftrtp.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTreesTest {

    /** Builds an ordered map from key/value pairs. */
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void mapsMergeRecursivelyScalarsAndListsReplace() {
        Map<String, Object> base = m(
                "a", 1,
                "list", List.of("x", "y"),
                "nested", m("keep", true, "change", "old", "deep", m("k", 1, "j", 2)));
        Map<String, Object> over = m(
                "list", List.of("z"),
                "nested", m("change", "new", "deep", m("j", 3)),
                "extra", "e");

        Map<String, Object> merged = ConfigTrees.merge(base, over);

        assertEquals(m(
                "a", 1,
                "list", List.of("z"),
                "nested", m("keep", true, "change", "new", "deep", m("k", 1, "j", 3)),
                "extra", "e"), merged);
        // inputs untouched
        assertEquals(List.of("x", "y"), base.get("list"));
        assertEquals("old", ((Map<?, ?>) base.get("nested")).get("change"));
    }

    @Test
    void opaqueMapsReplaceWholesale() {
        Map<String, Object> base = m("cooldown", m("base", "5m", "tiers", m("vip", "2m", "mvp", "30s")));
        Map<String, Object> over = m("cooldown", m("tiers", m("elite", "10s")));

        Map<String, Object> merged = ConfigTrees.merge(base, over, p -> p.equals("cooldown.tiers"));

        assertEquals(m("cooldown", m("base", "5m", "tiers", m("elite", "10s"))), merged);
    }

    @Test
    void mapReplacesScalarAndViceVersa() {
        assertEquals(m("a", m("b", 1)), ConfigTrees.merge(m("a", "x"), m("a", m("b", 1))));
        assertEquals(m("a", "x"), ConfigTrees.merge(m("a", m("b", 1)), m("a", "x")));
    }

    @Test
    void mergeResultIsIndependentCopy() {
        Map<String, Object> base = m("n", m("k", new ArrayList<>(List.of("a"))));
        Map<String, Object> merged = ConfigTrees.merge(base, m());
        ((Map<String, Object>) merged.get("n")).put("k", "changed");
        assertEquals(List.of("a"), ((Map<?, ?>) base.get("n")).get("k"));
    }

    @Test
    void findMissingReportsSectionsOnceAndHonoursSkip() {
        Map<String, Object> defaults = m(
                "a", 1,
                "sec", m("x", 1, "y", 2),
                "newsec", m("p", 1, "q", 2),
                "worlds", m("defaults", m("k", 1, "new", 2), "world", m()),
                "tiers", m("vip", "1m"));
        Map<String, Object> actual = m(
                "a", 5,
                "sec", m("x", 9),
                "worlds", m("defaults", m("k", 1)),
                "tiers", m("other", "5s"));
        List<String> missing = new ArrayList<>();
        ConfigTrees.findMissing(defaults, actual,
                p -> p.startsWith("tiers.") || (p.startsWith("worlds.") && !p.startsWith("worlds.defaults")),
                (p, v) -> missing.add(p));
        assertEquals(List.of("sec.y", "newsec", "worlds.defaults.new"), missing);
    }

    @Test
    void findUnknownReportsTyposWithDisplayPrefix() {
        Map<String, Object> schema = m("area", m("min-radius", 0, "max-radius", 1), "cooldown", m("tiers", m("vip", 1)));
        Map<String, Object> actual = m("area", m("max-raduis", 5), "cooldown", m("tiers", m("anything", 1)), "bogus", 1);
        List<String> unknown = new ArrayList<>();
        ConfigTrees.findUnknown(schema, actual, p -> p.equals("cooldown.tiers"), "worlds.world", unknown::add);
        assertEquals(List.of("worlds.world.area.max-raduis", "worlds.world.bogus"), unknown);
    }

    @Test
    void getByPath() {
        Map<String, Object> root = m("a", m("b", m("c", 3)));
        assertEquals(3, ConfigTrees.get(root, "a.b.c"));
        assertEquals(null, ConfigTrees.get(root, "a.x.c"));
        assertEquals(null, ConfigTrees.get(root, "a.b.c.d"));
    }
}

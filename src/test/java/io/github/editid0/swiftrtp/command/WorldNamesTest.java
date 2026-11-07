package io.github.editid0.swiftrtp.command;

import io.github.editid0.swiftrtp.listener.SignText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldNamesTest {

    @Test
    void selectableFiltersDisabledUnconfiguredAndPermission() {
        Map<String, WorldNames.Access> cfg = Map.of(
                "world", new WorldNames.Access(true, null),
                "world_nether", new WorldNames.Access(true, "rtp.nether"),
                "world_the_end", new WorldNames.Access(false, null),
                "vip", new WorldNames.Access(true, "rtp.vip"),
                "blankperm", new WorldNames.Access(true, " "));
        List<String> loaded = List.of("world", "world_nether", "world_the_end", "vip", "lobby", "blankperm");
        List<String> result = WorldNames.selectable(loaded, n -> Optional.ofNullable(cfg.get(n)), "rtp.vip"::equals);
        assertEquals(List.of("world", "vip", "blankperm"), result);
    }

    @Test
    void suggestionQuoting() {
        Set<String> reserved = Set.of("admin", "help");
        assertEquals("world_nether", WorldNames.suggestionText("world_nether", reserved));
        assertEquals("\"admin\"", WorldNames.suggestionText("admin", reserved));
        assertEquals("\"Admin\"", WorldNames.suggestionText("Admin", reserved));
        assertEquals("\"my world\"", WorldNames.suggestionText("my world", reserved));
        assertEquals("\"a\\\"b\"", WorldNames.suggestionText("a\"b", reserved));
    }

    @Test
    void prefixMatching() {
        assertTrue(WorldNames.matchesPrefix("world_nether", "WOR"));
        assertTrue(WorldNames.matchesPrefix("admin", "\"adm"));
        assertTrue(WorldNames.matchesPrefix("anything", ""));
        assertFalse(WorldNames.matchesPrefix("world", "nether"));
    }

    @Test
    void resolvePrefersExactThenUniqueCaseInsensitive() {
        assertEquals("world", WorldNames.resolve("world", List.of("World", "WORLD", "world")));
        assertEquals("World", WorldNames.resolve("world", List.of("World", "nether")));
        assertNull(WorldNames.resolve("world", List.of("World", "WORLD")));
        assertNull(WorldNames.resolve("missing", List.of("world")));
    }

    @Test
    void signHeaderAndWorldLine() {
        assertTrue(SignText.isHeader("  [rtp] ", "[RTP]"));
        assertFalse(SignText.isHeader("[RTP]x", "[RTP]"));
        assertFalse(SignText.isHeader("", " "));
        assertFalse(SignText.isHeader(null, "[RTP]"));
        assertEquals("", SignText.worldLine(null));
        assertEquals("world", SignText.worldLine("  world "));
    }
}

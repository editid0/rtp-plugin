package io.github.editid0.swiftrtp.search;

import io.github.editid0.swiftrtp.config.WorldSettings.FilterMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeMatcherTest {

    @Test
    void globBasics() {
        assertTrue(BiomeMatcher.glob("*ocean*", "minecraft:deep_cold_ocean"));
        assertTrue(BiomeMatcher.glob("*ocean*", "minecraft:ocean"));
        assertFalse(BiomeMatcher.glob("*ocean*", "minecraft:plains"));
        assertTrue(BiomeMatcher.glob("minecraft:*_river", "minecraft:frozen_river"));
        assertFalse(BiomeMatcher.glob("minecraft:*_river", "minecraft:river"));
        assertTrue(BiomeMatcher.glob("minecraft:?lains", "minecraft:plains"));
        assertTrue(BiomeMatcher.glob("*", ""));
        assertFalse(BiomeMatcher.glob("a", ""));
        assertTrue(BiomeMatcher.glob("a*b*c", "axxbyyc"));
        assertFalse(BiomeMatcher.glob("a*b*c", "axxbyy"));
    }

    @Test
    void normalisation() {
        assertEquals("minecraft:plains", BiomeMatcher.normalisePattern("plains"));
        assertEquals("minecraft:plains", BiomeMatcher.normalisePattern("  Minecraft:PLAINS "));
        assertEquals("*ocean*", BiomeMatcher.normalisePattern("*OCEAN*"));
        assertEquals("minecraft:snowy_*", BiomeMatcher.normalisePattern("snowy_*"));
        assertEquals("terralith:*", BiomeMatcher.normalisePattern("terralith:*"));
    }

    @Test
    void blacklist() {
        BiomeMatcher m = BiomeMatcher.of(FilterMode.BLACKLIST, Set.of("*ocean*", "minecraft:*_river", "river"));
        assertFalse(m.allows("minecraft:warm_ocean"));
        assertFalse(m.allows("terralith:warm_ocean"));
        assertFalse(m.allows("minecraft:river"));
        assertFalse(m.allows("minecraft:frozen_river"));
        assertTrue(m.allows("minecraft:plains"));
        assertTrue(m.allows("MINECRAFT:PLAINS"));
        assertFalse(m.isUnrestricted());
    }

    @Test
    void whitelistExactKeys() {
        BiomeMatcher m = BiomeMatcher.of(FilterMode.WHITELIST, List.of("plains", "minecraft:forest"));
        assertTrue(m.allows("minecraft:plains"));
        assertTrue(m.allows("minecraft:forest"));
        assertFalse(m.allows("minecraft:birch_forest"));
        assertFalse(m.allows("other:plains"));
    }

    @Test
    void emptyListsAllowEverything() {
        assertTrue(BiomeMatcher.of(FilterMode.WHITELIST, Set.of()).isUnrestricted());
        assertTrue(BiomeMatcher.of(FilterMode.WHITELIST, Set.of()).allows("minecraft:anything"));
        assertTrue(BiomeMatcher.of(FilterMode.BLACKLIST, List.of(" ")).allows("minecraft:anything"));
    }
}

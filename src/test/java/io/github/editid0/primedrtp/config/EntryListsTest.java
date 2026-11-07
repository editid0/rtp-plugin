package io.github.editid0.primedrtp.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryListsTest {

    private static final Map<String, String> SINGLES = Map.of(
            "minecraft:lava", "LAVA", "minecraft:oak_leaves", "OAK_LEAVES", "minecraft:magma_block", "MAGMA");
    private static final Map<String, List<String>> TAGS = Map.of(
            "minecraft:leaves", List.of("OAK_LEAVES", "BIRCH_LEAVES"));

    private static Set<String> resolve(List<String> entries, List<String> warnings) {
        return EntryLists.resolve(entries, "p", warnings::add, SINGLES::get, TAGS::get);
    }

    @Test
    void normalizeKey() {
        assertEquals("minecraft:lava", EntryLists.normalizeKey("LAVA"));
        assertEquals("minecraft:lava", EntryLists.normalizeKey(" minecraft:Lava "));
        assertEquals("minecraft:leaves", EntryLists.normalizeKey("#leaves"));
        assertEquals("custom:thing", EntryLists.normalizeKey("custom:thing"));
        assertEquals("minecraft:magma_block", EntryLists.normalizeKey("Magma Block"));
    }

    @Test
    void validKey() {
        assertTrue(EntryLists.isValidKey("minecraft:entity.enderman.teleport"));
        assertTrue(EntryLists.isValidKey("my-pack:sub/path_1"));
        assertFalse(EntryLists.isValidKey("minecraft:"));
        assertFalse(EntryLists.isValidKey(":x"));
        assertFalse(EntryLists.isValidKey("minecraft:a b"));
        assertFalse(EntryLists.isValidKey("Minecraft:x"));
        assertFalse(EntryLists.isValidKey("noColon"));
    }

    @Test
    void resolvesSinglesAndTagsCaseInsensitively() {
        List<String> warnings = new ArrayList<>();
        Set<String> out = resolve(List.of("lava", "MINECRAFT:MAGMA_BLOCK", "#minecraft:leaves"), warnings);
        assertEquals(Set.of("LAVA", "MAGMA", "OAK_LEAVES", "BIRCH_LEAVES"), out);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void negationRemovesEarlierEntries() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Set.of("BIRCH_LEAVES"), resolve(List.of("#leaves", "!oak_leaves"), warnings));
        assertEquals(Set.of("LAVA"), resolve(List.of("lava", "#leaves", "!#leaves"), warnings));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unknownEntriesWarnAndSkip() {
        List<String> warnings = new ArrayList<>();
        Set<String> out = resolve(List.of("lava", "notablock", "#nosuchtag", "bad name!", ""), warnings);
        assertEquals(Set.of("LAVA"), out);
        assertEquals(3, warnings.size());
        assertTrue(warnings.getFirst().startsWith("p: "));
    }

    @Test
    void biomeNormalization() {
        assertEquals("minecraft:plains", EntryLists.normalizeBiome("Plains"));
        assertEquals("minecraft:plains", EntryLists.normalizeBiome("minecraft:plains"));
        assertEquals("*ocean*", EntryLists.normalizeBiome("*OCEAN*"));
        assertEquals("minecraft:*_river", EntryLists.normalizeBiome("minecraft:*_river"));
        assertEquals("terralith:*", EntryLists.normalizeBiome("terralith:*"));
        assertNull(EntryLists.normalizeBiome("  "));
    }

    @Test
    void itemParsing() {
        assertEquals(new EntryLists.ItemEntry("minecraft:diamond", 2), EntryLists.parseItem("diamond:2"));
        assertEquals(new EntryLists.ItemEntry("minecraft:diamond", 2), EntryLists.parseItem("minecraft:diamond:2"));
        assertEquals(new EntryLists.ItemEntry("minecraft:diamond", 1), EntryLists.parseItem("DIAMOND"));
        assertEquals(new EntryLists.ItemEntry("minecraft:diamond", 1), EntryLists.parseItem("minecraft:diamond"));
        assertEquals(new EntryLists.ItemEntry("minecraft:ender_pearl", 3), EntryLists.parseItem("ender_pearl x3"));
        assertEquals(new EntryLists.ItemEntry("minecraft:ender_pearl", 3), EntryLists.parseItem("3x ender_pearl"));
        assertNull(EntryLists.parseItem("diamond:0"));
        assertNull(EntryLists.parseItem("diamond:-1"));
        assertNull(EntryLists.parseItem(""));
        assertNull(EntryLists.parseItem("bad name!:2"));
    }

    @Test
    void aliases() {
        assertEquals("wild", EntryLists.normalizeAlias("/Wild"));
        assertEquals("random-tp", EntryLists.normalizeAlias("random-tp"));
        assertNull(EntryLists.normalizeAlias("two words"));
        assertNull(EntryLists.normalizeAlias(""));
    }
}

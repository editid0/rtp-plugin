package io.github.editid0.primedrtp.search;

import io.github.editid0.primedrtp.config.WorldSettings.BiomeFilter;
import io.github.editid0.primedrtp.config.WorldSettings.ChunkPreference;
import io.github.editid0.primedrtp.config.WorldSettings.FilterMode;
import io.github.editid0.primedrtp.config.WorldSettings.SearchMode;
import io.github.editid0.primedrtp.config.WorldSettings.SearchSettings;
import io.github.editid0.primedrtp.search.SafetyChecker.Mode;
import io.github.editid0.primedrtp.search.SafetyChecker.Rules;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure helpers of the searcher (no server). */
class DefaultLocationSearcherTest {

    private static SearchSettings search(SearchMode mode, Integer minY, Integer maxY) {
        return new SearchSettings(mode, minY, maxY, 30, true, true, Set.of(), Set.of(), ChunkPreference.ANY,
                new BiomeFilter(FilterMode.BLACKLIST, Set.of()), false, 0);
    }

    @Test
    void autoModeByEnvironment() {
        assertEquals(Mode.SURFACE, DefaultLocationSearcher.resolveRules(search(SearchMode.AUTO, null, null),
                World.Environment.NORMAL, -64, 320, 384).mode());
        assertEquals(Mode.CAVE, DefaultLocationSearcher.resolveRules(search(SearchMode.AUTO, null, null),
                World.Environment.NETHER, 0, 256, 128).mode());
        assertEquals(Mode.END, DefaultLocationSearcher.resolveRules(search(SearchMode.AUTO, null, null),
                World.Environment.THE_END, 0, 256, 256).mode());
        assertEquals(Mode.CAVE, DefaultLocationSearcher.resolveRules(search(SearchMode.CAVE, null, null),
                World.Environment.NORMAL, -64, 320, 384).mode());
    }

    @Test
    void feetRangeDefaults() {
        Rules overworld = DefaultLocationSearcher.resolveRules(search(SearchMode.SURFACE, null, null),
                World.Environment.NORMAL, -64, 320, 384);
        assertEquals(-63, overworld.minFeetY());
        assertEquals(318, overworld.maxFeetY());
        Rules nether = DefaultLocationSearcher.resolveRules(search(SearchMode.AUTO, null, null),
                World.Environment.NETHER, 0, 256, 128);
        assertEquals(1, nether.minFeetY());
        assertEquals(122, nether.maxFeetY()); // below the bedrock roof (123..127)
        Rules custom = DefaultLocationSearcher.resolveRules(search(SearchMode.AUTO, 32, 100),
                World.Environment.NETHER, 0, 256, 128);
        assertEquals(32, custom.minFeetY());
        assertEquals(100, custom.maxFeetY());
        Rules aboveRoof = DefaultLocationSearcher.resolveRules(search(SearchMode.CAVE, 32, 200),
                World.Environment.NETHER, 0, 256, 128);
        assertEquals(122, aboveRoof.maxFeetY(), "explicit max-y may not reach the bedrock roof");
        Rules netherSurface = DefaultLocationSearcher.resolveRules(search(SearchMode.SURFACE, null, null),
                World.Environment.NETHER, 0, 256, 128);
        assertEquals(122, netherSurface.maxFeetY(), "forced surface mode in the nether never lands on the roof");
        Rules clamped = DefaultLocationSearcher.resolveRules(search(SearchMode.SURFACE, -1000, 1000),
                World.Environment.NORMAL, -64, 320, 384);
        assertEquals(-63, clamped.minFeetY());
        assertEquals(318, clamped.maxFeetY());
    }

    @Test
    void precheckHeights() {
        Rules s = new Rules(Mode.SURFACE, -63, 318, true, true);
        assertArrayEquals(new int[] {63, 111}, DefaultLocationSearcher.precheckHeights(s, 63));
        Rules c = new Rules(Mode.CAVE, 1, 121, false, true);
        assertArrayEquals(new int[] {31, 61, 91}, DefaultLocationSearcher.precheckHeights(c, 32));
        Rules narrow = new Rules(Mode.SURFACE, 70, 80, true, true);
        assertArrayEquals(new int[] {70, 80}, DefaultLocationSearcher.precheckHeights(narrow, 63));
    }

    @Test
    void within() {
        long[] pts = {SearchOptions.pack(0, 0), SearchOptions.pack(-1000, 500)};
        assertTrue(DefaultLocationSearcher.within(pts, 3, 4, 6));
        assertFalse(DefaultLocationSearcher.within(pts, 3, 4, 5)); // exactly at radius is allowed
        assertTrue(DefaultLocationSearcher.within(pts, -1010, 500, 11));
        assertFalse(DefaultLocationSearcher.within(new long[0], 0, 0, 100));
    }
}

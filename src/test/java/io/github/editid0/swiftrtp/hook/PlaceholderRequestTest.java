package io.github.editid0.swiftrtp.hook;

import io.github.editid0.swiftrtp.hook.PlaceholderRequest.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholderRequestTest {

    private static void check(String params, Kind kind, String world) {
        assertEquals(new PlaceholderRequest(kind, world), PlaceholderRequest.parse(params), params);
    }

    @Test
    void bareKindsUseResolvedWorld() {
        check("cooldown", Kind.COOLDOWN, null);
        check("cooldown_seconds", Kind.COOLDOWN_SECONDS, null);
        check("ready", Kind.READY, null);
        check("pending", Kind.PENDING, null);
        check("pool_size", Kind.POOL_SIZE, null);
    }

    @Test
    void worldNamesWithUnderscoresAreKeptWhole() {
        check("cooldown_world_nether", Kind.COOLDOWN, "world_nether");
        check("cooldown_seconds_world_the_end", Kind.COOLDOWN_SECONDS, "world_the_end");
        check("ready_world", Kind.READY, "world");
        check("pool_size_world_nether", Kind.POOL_SIZE, "world_nether");
        check("pool_target_my_big_world", Kind.POOL_TARGET, "my_big_world");
        check("pool_percent_world", Kind.POOL_PERCENT, "world");
    }

    @Test
    void longestPrefixWins() {
        // "cooldown_seconds_x" is the seconds placeholder for world "x", not cooldown for world "seconds_x".
        check("cooldown_seconds_x", Kind.COOLDOWN_SECONDS, "x");
        check("cooldown_secondsworld", Kind.COOLDOWN, "secondsworld");
    }

    @Test
    void kindIsCaseInsensitiveWorldKeepsCase() {
        check("COOLDOWN_World_Nether", Kind.COOLDOWN, "World_Nether");
        check("Pool_Size_Lobby", Kind.POOL_SIZE, "Lobby");
    }

    @Test
    void unknownOrMalformed() {
        assertNull(PlaceholderRequest.parse(null));
        assertNull(PlaceholderRequest.parse(""));
        assertNull(PlaceholderRequest.parse("nope"));
        assertNull(PlaceholderRequest.parse("cooldown_"));
        assertNull(PlaceholderRequest.parse("pending_world"));
        assertNull(PlaceholderRequest.parse("pool"));
        assertNull(PlaceholderRequest.parse("cooldownworld"));
    }

    @Test
    void ceilSeconds() {
        assertEquals(0, PlaceholderValues.ceilSeconds(Duration.ZERO));
        assertEquals(1, PlaceholderValues.ceilSeconds(Duration.ofMillis(1)));
        assertEquals(5, PlaceholderValues.ceilSeconds(Duration.ofSeconds(5)));
        assertEquals(6, PlaceholderValues.ceilSeconds(Duration.ofMillis(5001)));
    }
}

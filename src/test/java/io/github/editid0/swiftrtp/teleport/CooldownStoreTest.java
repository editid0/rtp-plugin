package io.github.editid0.swiftrtp.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void remainingAndExpiry() {
        CooldownStore store = new CooldownStore();
        store.set(A, "world", 1_000 + 5_000, 1_000);
        assertEquals(5_000, store.remainingMillis(A, "world", 1_000));
        assertEquals(1_000, store.remainingMillis(A, "world", 5_000));
        assertEquals(0, store.remainingMillis(A, "world", 6_000));
        assertEquals(0, store.remainingMillis(A, "other", 1_000));
        assertEquals(0, store.remainingMillis(B, "world", 1_000));
    }

    @Test
    void resetOneOrAll() {
        CooldownStore store = new CooldownStore();
        store.set(A, "world", 10_000, 0);
        store.set(A, "*", 10_000, 0);
        store.reset(A, "world");
        assertEquals(0, store.remainingMillis(A, "world", 0));
        assertEquals(10_000, store.remainingMillis(A, "*", 0));
        store.reset(A, null);
        assertEquals(0, store.remainingMillis(A, "*", 0));
        assertEquals(0, store.playerCount());
    }

    @Test
    void dirtyFlag() {
        CooldownStore store = new CooldownStore();
        assertFalse(store.isDirty());
        store.set(A, "world", 10_000, 0);
        assertTrue(store.clearDirty());
        assertFalse(store.isDirty());
        store.reset(B, null); // nothing to remove
        assertFalse(store.isDirty());
    }

    @Test
    void pruneRemovesExpired() {
        CooldownStore store = new CooldownStore();
        store.set(A, "world", 100, 0);
        store.set(B, "world", 10_000, 0);
        store.prune(500);
        assertEquals(1, store.playerCount());
    }

    @Test
    void jsonRoundTripSkipsExpired() {
        CooldownStore store = new CooldownStore();
        store.set(A, "world", 10_000, 0);
        store.set(A, "*", 2_000, 0);
        store.set(B, "nether", 50_000, 0);
        String json = store.toJson(5_000); // "*" of A already expired at 5000

        CooldownStore loaded = new CooldownStore();
        assertEquals(2, loaded.loadJson(json, 5_000));
        assertEquals(5_000, loaded.remainingMillis(A, "world", 5_000));
        assertEquals(0, loaded.remainingMillis(A, "*", 5_000));
        assertEquals(45_000, loaded.remainingMillis(B, "nether", 5_000));
    }

    @Test
    void loadKeepsLaterExpiryAndSkipsGarbage() {
        CooldownStore store = new CooldownStore();
        store.set(A, "world", 20_000, 0);
        String json = """
                {"version":1,"cooldowns":{
                  "00000000-0000-0000-0000-00000000000a":{"world":10000,"bad":"x"},
                  "not-a-uuid":{"world":99999},
                  "00000000-0000-0000-0000-00000000000b":{"world":30000}
                }}""";
        assertEquals(2, store.loadJson(json, 0));
        assertEquals(20_000, store.remainingMillis(A, "world", 0));
        assertEquals(30_000, store.remainingMillis(B, "world", 0));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> new CooldownStore().loadJson("[1,2]", 0));
        assertThrows(IllegalArgumentException.class, () -> new CooldownStore().loadJson("{nope", 0));
    }
}

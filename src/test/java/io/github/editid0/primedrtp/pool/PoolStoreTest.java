package io.github.editid0.primedrtp.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PoolStoreTest {

    private static PoolStore.StoredEntry entry(String name, double x) {
        return new PoolStore.StoredEntry(UUID.randomUUID().toString(), name, x, 64.0, -1234.5, -91.25f,
                1_700_000_000_123L);
    }

    @Test
    void jsonRoundTrip() throws IOException {
        PoolStore.Snapshot s = new PoolStore.Snapshot(42L, Map.of("u", "area:x|search:y"),
                List.of(entry("world", 100.5), entry("world_nether", -7.5)));
        PoolStore.Snapshot back = PoolStore.fromJson(PoolStore.toJson(s));
        assertEquals(PoolStore.FORMAT_VERSION, back.version);
        assertEquals(42L, back.savedAt);
        assertEquals(s.fingerprints, back.fingerprints);
        assertEquals(s.entries, back.entries);
    }

    @Test
    void malformedEntriesDroppedAndBadFilesRejected() throws IOException {
        String json = "{\"version\":1,\"entries\":[null,{\"worldName\":\"w\",\"x\":1},"
                + "{\"worldId\":\"" + UUID.randomUUID() + "\",\"worldName\":\"w\",\"x\":1,\"y\":2,\"z\":3}]}";
        PoolStore.Snapshot s = PoolStore.fromJson(json);
        assertEquals(1, s.entries.size());
        assertNotNull(s.fingerprints);
        assertThrows(IOException.class, () -> PoolStore.fromJson("{not json"));
        assertThrows(IOException.class, () -> PoolStore.fromJson("{\"version\":99}"));
        assertTrue(PoolStore.fromJson("").entries.isEmpty());
    }

    @Test
    void atomicWriteAndRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data").resolve("pools.json");
        assertTrue(PoolStore.read(file).entries.isEmpty(), "missing file is empty");
        PoolStore.Snapshot s = new PoolStore.Snapshot(1L, Map.of(), List.of(entry("world", 0.5)));
        PoolStore.write(file, s);
        PoolStore.write(file, s); // overwrite
        assertEquals(s.entries, PoolStore.read(file).entries);
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList(), "no temp files left behind");
        }
    }
}

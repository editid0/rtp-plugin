package io.github.editid0.primedrtp.pool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure persistence of pooled locations to {@code plugins/PrimedRTP/data/pools.json}: plain DTOs, Gson
 * (de)serialisation and an atomic file write (temp file + move). No Bukkit access, so it can run on any thread and is
 * unit-tested.
 */
final class PoolStore {

    static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PoolStore() {}

    /** One saved location. */
    static final class StoredEntry {
        String worldId;
        String worldName;
        double x;
        double y;
        double z;
        float yaw;
        long createdAt;

        StoredEntry() {}

        StoredEntry(String worldId, String worldName, double x, double y, double z, float yaw, long createdAt) {
            this.worldId = worldId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.createdAt = createdAt;
        }

        boolean isValid() {
            return worldId != null && worldName != null && Double.isFinite(x) && Double.isFinite(y)
                    && Double.isFinite(z) && Float.isFinite(yaw);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StoredEntry e && Objects.equals(worldId, e.worldId)
                    && Objects.equals(worldName, e.worldName) && Double.compare(x, e.x) == 0
                    && Double.compare(y, e.y) == 0 && Double.compare(z, e.z) == 0
                    && Float.compare(yaw, e.yaw) == 0 && createdAt == e.createdAt;
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, worldName, x, y, z, yaw, createdAt);
        }

        @Override
        public String toString() {
            return worldName + "(" + x + "," + y + "," + z + ")";
        }
    }

    /** Whole file. {@code fingerprints}: world UUID -> {@link SettingsFingerprint} at save time. */
    static final class Snapshot {
        int version = FORMAT_VERSION;
        long savedAt;
        Map<String, String> fingerprints = new LinkedHashMap<>();
        List<StoredEntry> entries = new ArrayList<>();

        Snapshot() {}

        Snapshot(long savedAt, Map<String, String> fingerprints, List<StoredEntry> entries) {
            this.savedAt = savedAt;
            this.fingerprints = new LinkedHashMap<>(fingerprints);
            this.entries = new ArrayList<>(entries);
        }
    }

    static String toJson(Snapshot snapshot) {
        return GSON.toJson(snapshot);
    }

    /**
     * Parses a snapshot, dropping malformed entries. Unknown future versions and syntax errors yield an empty
     * snapshot via {@link IOException}.
     */
    static Snapshot fromJson(String json) throws IOException {
        Snapshot s;
        try {
            s = GSON.fromJson(json, Snapshot.class);
        } catch (JsonParseException e) {
            throw new IOException("malformed pools.json: " + e.getMessage(), e);
        }
        if (s == null) {
            return new Snapshot();
        }
        if (s.version > FORMAT_VERSION) {
            throw new IOException("pools.json has unsupported version " + s.version);
        }
        if (s.fingerprints == null) {
            s.fingerprints = new LinkedHashMap<>();
        }
        List<StoredEntry> valid = new ArrayList<>();
        if (s.entries != null) {
            for (StoredEntry e : s.entries) {
                if (e != null && e.isValid()) {
                    valid.add(e);
                }
            }
        }
        s.entries = valid;
        return s;
    }

    /** Atomically replaces {@code file} (creating parent directories). */
    static void write(Path file, Snapshot snapshot) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, toJson(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Reads the file; a missing file yields an empty snapshot. */
    static Snapshot read(Path file) throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new Snapshot();
        }
        return fromJson(json);
    }
}

package io.github.editid0.swiftrtp.teleport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe in-memory cooldown table: player UUID -> (cooldown key -> expiry epoch millis). Pure (no Bukkit), the
 * clock is passed in. Serialises to/from JSON:
 * <pre>{"version":1,"cooldowns":{"&lt;uuid&gt;":{"world":1700000000000,"*":1700000005000}}}</pre>
 */
public final class CooldownStore {

    /** Key used when cooldowns are shared across worlds ({@code cooldown.per-world: false}). */
    public static final String SHARED_KEY = "*";

    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> expiries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** Remaining millis (0 if none/expired). Expired entries are pruned lazily. */
    public long remainingMillis(UUID player, String key, long now) {
        Map<String, Long> map = expiries.get(player);
        if (map == null) {
            return 0L;
        }
        Long expiry = map.get(key);
        if (expiry == null) {
            return 0L;
        }
        long left = expiry - now;
        if (left <= 0) {
            map.remove(key, expiry);
            return 0L;
        }
        return left;
    }

    /** Sets the expiry (epoch millis) for a key; a non-future expiry clears it. */
    public void set(UUID player, String key, long expiryMillis, long now) {
        if (expiryMillis <= now) {
            reset(player, key);
            return;
        }
        expiries.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).put(key, expiryMillis);
        dirty.set(true);
    }

    /** Clears one key, or every key of the player when {@code key} is null. */
    public void reset(UUID player, @Nullable String key) {
        if (key == null) {
            if (expiries.remove(player) != null) {
                dirty.set(true);
            }
            return;
        }
        Map<String, Long> map = expiries.get(player);
        if (map != null && map.remove(key) != null) {
            dirty.set(true);
            if (map.isEmpty()) {
                expiries.remove(player, map);
            }
        }
    }

    /** Removes every expired entry. */
    public void prune(long now) {
        for (Iterator<Map.Entry<UUID, ConcurrentHashMap<String, Long>>> it = expiries.entrySet().iterator(); it.hasNext(); ) {
            Map<String, Long> map = it.next().getValue();
            map.values().removeIf(expiry -> expiry <= now);
            if (map.isEmpty()) {
                it.remove();
            }
        }
    }

    public int playerCount() {
        return expiries.size();
    }

    /** True once since the last {@link #clearDirty()} if anything changed. */
    public boolean isDirty() {
        return dirty.get();
    }

    /** Atomically reads and clears the dirty flag. */
    public boolean clearDirty() {
        return dirty.getAndSet(false);
    }

    public void markDirty() {
        dirty.set(true);
    }

    /** Serialises all non-expired cooldowns. */
    public String toJson(long now) {
        JsonObject players = new JsonObject();
        expiries.forEach((id, map) -> {
            JsonObject keys = new JsonObject();
            map.forEach((key, expiry) -> {
                if (expiry > now) {
                    keys.addProperty(key, expiry);
                }
            });
            if (!keys.isEmpty()) {
                players.add(id.toString(), keys);
            }
        });
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.add("cooldowns", players);
        return GSON.toJson(root);
    }

    /**
     * Merges cooldowns from JSON, keeping the later expiry when a key already exists. Malformed entries are
     * skipped.
     *
     * @return number of entries loaded
     * @throws IllegalArgumentException if the document is not a JSON object
     */
    public int loadJson(String json, long now) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed cooldown JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Cooldown JSON root is not an object");
        }
        JsonElement cooldowns = parsed.getAsJsonObject().get("cooldowns");
        if (cooldowns == null || !cooldowns.isJsonObject()) {
            return 0;
        }
        int loaded = 0;
        for (Map.Entry<String, JsonElement> player : cooldowns.getAsJsonObject().entrySet()) {
            UUID id;
            try {
                id = UUID.fromString(player.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!player.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Long> fresh = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : player.getValue().getAsJsonObject().entrySet()) {
                try {
                    long expiry = entry.getValue().getAsLong();
                    if (expiry > now) {
                        fresh.put(entry.getKey(), expiry);
                    }
                } catch (RuntimeException ignored) {
                    // skip malformed value
                }
            }
            if (!fresh.isEmpty()) {
                ConcurrentHashMap<String, Long> target = expiries.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
                fresh.forEach((key, expiry) -> target.merge(key, expiry, Math::max));
                loaded += fresh.size();
            }
        }
        return loaded;
    }
}

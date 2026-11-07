package io.github.editid0.swiftrtp.config;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fully-resolved, immutable settings for one RTP-enabled world.
 * <p>
 * Built by {@link ConfigLoader} by deep-merging {@code worlds.defaults} with {@code worlds.<name>} in config.yml,
 * so every field is always non-null unless annotated {@link Nullable}. Every section is overridable per world.
 */
public record WorldSettings(
        String worldName,
        boolean enabled,
        /* Permission required to RTP into this world; null = no permission needed. */
        @Nullable String permission,
        AreaSettings area,
        SearchSettings search,
        PoolSettings pool,
        TeleportSettings teleport,
        CooldownSettings cooldown,
        CostSettings cost
) {

    /**
     * Returns a copy bound to a different world name (used for "enable-unlisted-worlds"). A {@code {world}} token
     * left in the template's permission is substituted with the new name.
     */
    public WorldSettings withWorldName(String name) {
        String perm = permission == null ? null : permission.replace("{world}", name);
        return new WorldSettings(name, enabled, perm, area, search, pool, teleport, cooldown, cost);
    }

    // ---------------------------------------------------------------------------------------------------------
    // area: where on the X/Z plane locations may be picked
    // ---------------------------------------------------------------------------------------------------------

    public enum CenterMode {
        /** World spawn location. */
        WORLD_SPAWN,
        /** {@link AreaSettings#centerX()} / {@link AreaSettings#centerZ()}. */
        FIXED,
        /** Centre of the world border. */
        WORLD_BORDER
    }

    public enum Shape {
        /** Uniform in a square ring: max(|dx|,|dz|) in [minRadius, maxRadius]. */
        SQUARE,
        /** Uniform by area in a circular ring: sqrt(dx²+dz²) in [minRadius, maxRadius]. */
        CIRCLE
    }

    public record AreaSettings(
            CenterMode centerMode,
            int centerX,
            int centerZ,
            Shape shape,
            int minRadius,
            int maxRadius,
            /* Clamp to (and never pick outside) the world border. */
            boolean respectWorldBorder,
            /* Blocks to keep away from the world border edge. */
            int borderPadding
    ) {}

    // ---------------------------------------------------------------------------------------------------------
    // search: how a candidate X/Z is turned into a safe standing position
    // ---------------------------------------------------------------------------------------------------------

    public enum SearchMode {
        /** SURFACE for normal worlds, CAVE for the nether, END for the end. */
        AUTO,
        /** Highest solid block (heightmap) with open sky. */
        SURFACE,
        /** Scan downward from maxY for an enclosed air pocket with a solid floor (nether-style). */
        CAVE,
        /** Surface search that rejects the void (end islands). */
        END
    }

    public enum ChunkPreference {
        /** Any chunk. */
        ANY,
        /** Only already-generated chunks (cheap, never generates new terrain). */
        GENERATED_ONLY,
        /** Only never-generated chunks (fresh, untouched terrain). */
        UNGENERATED_ONLY
    }

    public enum FilterMode { WHITELIST, BLACKLIST }

    /**
     * Biome filter. Entries are lower-case namespaced keys ({@code minecraft:plains}) or glob patterns
     * ({@code *ocean*}, {@code minecraft:*_river}); a bare name without namespace means {@code minecraft:}.
     * An empty BLACKLIST allows everything; an empty WHITELIST is treated as "allow everything" with a warning.
     */
    public record BiomeFilter(FilterMode mode, Set<String> biomes) {}

    public record SearchSettings(
            SearchMode mode,
            /* Lowest Y a player may be placed at (inclusive); null = world min height. */
            @Nullable Integer minY,
            /* Highest Y a player may be placed at (inclusive); null = world max (for CAVE in the nether use ~120). */
            @Nullable Integer maxY,
            /* Max candidate columns tried per search before giving up. */
            int maxAttempts,
            /* SURFACE only: the standing block must see the sky (no roofs/overhangs). */
            boolean requireSkyAccess,
            /* Reject standing on/in water or lava. */
            boolean avoidLiquids,
            /* Blocks that may never be stood on (lava, magma, cactus, ...). */
            Set<Material> unsafeFloor,
            /* Blocks that may not occupy the 2 body blocks or be directly adjacent (fire, sweet berry bush, ...). */
            Set<Material> unsafeNearby,
            ChunkPreference chunkPreference,
            BiomeFilter biomes,
            /* Consult claim/region hooks (WorldGuard etc.) and reject protected spots. */
            boolean avoidClaims,
            /* Reject candidates within this many blocks of any online player in the world (0 = off). */
            int minDistanceFromPlayers
    ) {}

    // ---------------------------------------------------------------------------------------------------------
    // pool: pre-generated, pre-loaded ready-to-use locations
    // ---------------------------------------------------------------------------------------------------------

    public record PoolSettings(
            boolean enabled,
            /* Number of ready locations to keep for this world. */
            int size,
            /* Chunk radius kept loaded via plugin chunk tickets around each pooled location (0 = only its chunk). */
            int keepLoadedRadius,
            /* Chunk radius generated (then allowed to unload) around each pooled location. >= keepLoadedRadius. */
            int pregenRadius,
            /* Pooled locations older than this are discarded and replaced (ZERO = never). */
            Duration expireAfter,
            /* Minimum block distance between two pooled locations (spreads players out). */
            int minSpacing
    ) {}

    // ---------------------------------------------------------------------------------------------------------
    // teleport: warmup, protection and effects
    // ---------------------------------------------------------------------------------------------------------

    /** A sound by namespaced key, e.g. {@code minecraft:entity.enderman.teleport}. */
    public record SoundSpec(String key, float volume, float pitch) {}

    /** A particle by namespaced key, e.g. {@code minecraft:portal}. */
    public record ParticleSpec(String key, int count, double spread) {}

    /** A potion effect by namespaced key, e.g. {@code minecraft:slow_falling}. */
    public record PotionSpec(String key, Duration duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}

    public record EffectSettings(
            /* Show title/subtitle on arrival (messages: teleport.title / teleport.subtitle). */
            boolean arrivalTitle,
            /* Show countdown in the action bar during warmup. */
            boolean actionBarCountdown,
            /* Show a boss bar countdown during warmup. */
            boolean bossBarCountdown,
            @Nullable SoundSpec warmupTickSound,
            @Nullable SoundSpec teleportSound,
            @Nullable ParticleSpec warmupParticles,
            @Nullable ParticleSpec arrivalParticles,
            List<PotionSpec> potionEffects
    ) {}

    public record TeleportSettings(
            Duration warmup,
            boolean cancelOnMove,
            /* Blocks the player may drift during warmup before it is cancelled. */
            double moveTolerance,
            boolean cancelOnDamage,
            /* Full damage immunity after arrival. */
            Duration invulnerability,
            /* Cancel the next fall damage within this window after arrival (ZERO = off). */
            Duration fallDamageProtection,
            EffectSettings effects,
            /* Commands run after a successful teleport: "console: cmd", "player: cmd" or "op: cmd"; placeholders
               {player}, {uuid}, {world}, {x}, {y}, {z}. */
            List<String> commands,
            /* If the chosen location turns unsafe at the last moment, how many replacements to try. */
            int maxRetries
    ) {}

    // ---------------------------------------------------------------------------------------------------------
    // cooldown & cost
    // ---------------------------------------------------------------------------------------------------------

    public record CooldownSettings(
            Duration base,
            /* Permission tier name -> cooldown. Player with "swiftrtp.cooldown.<tier>" gets that cooldown;
               the SHORTEST applicable cooldown wins. Ordered as written in config. */
            Map<String, Duration> tiers,
            /* true = separate cooldown per world; false = one cooldown shared by all worlds using the same key. */
            boolean perWorld
    ) {}

    public record ItemCost(Material material, int amount) {}

    public record CostSettings(
            /* Vault money; 0 = free. Ignored when no economy is hooked. */
            double money,
            int xpLevels,
            List<ItemCost> items,
            /* Refund everything if the teleport fails after charging. */
            boolean refundOnFailure
    ) {
        public boolean isFree() {
            return money <= 0 && xpLevels <= 0 && items.isEmpty();
        }
    }
}

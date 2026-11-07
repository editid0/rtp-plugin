package io.github.editid0.swiftrtp.config;

import io.github.editid0.swiftrtp.config.WorldSettings.CenterMode;
import io.github.editid0.swiftrtp.config.WorldSettings.FilterMode;
import io.github.editid0.swiftrtp.config.WorldSettings.SearchMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses the real bundled config.yml (and variations) with fake server lookups. */
class SettingsParserTest {

    /** Lookups that need no server: Material.matchMaterial is a pure enum lookup; registries accept known keys. */
    static final class FakeLookups implements ConfigLookups {
        @Override
        public @Nullable Material block(String key) {
            return Material.matchMaterial(key);
        }

        @Override
        public @Nullable Material item(String key) {
            Material m = Material.matchMaterial(key);
            return m == Material.AIR ? null : m;
        }

        @Override
        public @Nullable Collection<Material> blockTag(String key) {
            return key.equals("minecraft:leaves") ? List.of(Material.OAK_LEAVES, Material.BIRCH_LEAVES) : null;
        }

        @Override
        public boolean exists(Kind kind, String key) {
            return !key.contains("nonexistent");
        }
    }

    private final List<String> warnings = new ArrayList<>();

    static Map<String, Object> bundled() throws Exception {
        try (InputStream in = SettingsParserTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml on test classpath");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return ConfigTrees.toMap(yaml);
        }
    }

    static Map<String, Object> yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return ConfigTrees.toMap(yaml);
    }

    private PluginSettings parse(Map<String, Object> user) throws Exception {
        return new SettingsParser(new FakeLookups(), warnings::add).parse(user, bundled());
    }

    @Test
    void bundledConfigParsesWithoutWarnings() throws Exception {
        PluginSettings s = parse(bundled());
        assertEquals(List.of(), warnings);

        assertEquals(1, s.configVersion());
        assertFalse(s.debug());
        assertEquals("world", s.defaultWorld());
        assertEquals(List.of("wild", "randomtp", "wilderness"), s.commandAliases());
        assertEquals(Map.of(), s.worldRedirects());
        assertFalse(s.enableUnlistedWorlds());
        assertEquals(List.of("world", "world_nether", "world_the_end"), List.copyOf(s.worlds().keySet()));

        assertEquals(4, s.performance().maxConcurrentChunkLoads());
        assertEquals(Duration.ofSeconds(10), s.performance().startupDelay());
        assertTrue(s.storage().persistPools());
        assertEquals("[RTP]", s.triggers().signs().header());
        assertNull(s.triggers().firstJoin().world());

        WorldSettings d = s.defaults();
        assertEquals(SettingsParser.DEFAULTS_NAME, d.worldName());
        assertNull(d.permission());
        assertEquals(500, d.area().minRadius());
        assertEquals(5000, d.area().maxRadius());
        assertEquals(CenterMode.WORLD_SPAWN, d.area().centerMode());
        assertNull(d.search().minY());
        assertTrue(d.search().unsafeFloor().containsAll(Set.of(Material.LAVA, Material.MAGMA_BLOCK, Material.OAK_LEAVES)));
        assertTrue(d.search().unsafeNearby().contains(Material.FIRE));
        assertEquals(FilterMode.BLACKLIST, d.search().biomes().mode());
        assertEquals(Set.of("*ocean*", "*river*"), d.search().biomes().biomes());
        assertEquals(Duration.ofMinutes(5), d.cooldown().base());
        assertEquals(List.of("vip", "mvp"), List.copyOf(d.cooldown().tiers().keySet()));
        assertEquals(Duration.ofSeconds(30), d.cooldown().tiers().get("mvp"));
        assertTrue(d.cost().isFree());
        assertEquals("minecraft:entity.enderman.teleport", d.teleport().effects().teleportSound().key());
        assertEquals(0.6f, d.teleport().effects().warmupTickSound().volume());
        assertEquals("minecraft:portal", d.teleport().effects().warmupParticles().key());

        WorldSettings nether = s.worlds().get("world_nether");
        assertEquals(SearchMode.CAVE, nether.search().mode());
        assertEquals(120, nether.search().maxY());
        assertEquals(32, nether.search().minY());
        assertEquals(2000, nether.area().maxRadius());
        assertEquals(Set.of(), nether.search().biomes().biomes());
        assertFalse(nether.search().unsafeFloor().contains(Material.OAK_LEAVES)); // list replaced
        assertEquals(5, nether.pool().size());
        assertEquals(10, s.worlds().get("world").pool().size()); // inherited

        WorldSettings end = s.worlds().get("world_the_end");
        assertEquals(SearchMode.END, end.search().mode());
        assertEquals(1000, end.area().minRadius());
        assertEquals(CenterMode.FIXED, end.area().centerMode());
        assertEquals(Set.of("minecraft:the_end", "minecraft:small_end_islands"), end.search().biomes().biomes());
        // inherited deep values still present
        assertEquals(Duration.ofSeconds(3), end.teleport().warmup());
    }

    @Test
    void partialUserConfigFallsBackToBundled() throws Exception {
        PluginSettings s = parse(yaml("""
                debug: true
                default-world: survival
                worlds:
                  defaults:
                    pool:
                      size: 20
                  survival:
                    permission: "swiftrtp.world.{world}"
                    area:
                      max-radius: 10000
                """));
        assertEquals(List.of(), warnings);
        assertTrue(s.debug());
        assertEquals(List.of("survival"), List.copyOf(s.worlds().keySet()));
        WorldSettings w = s.worlds().get("survival");
        assertEquals("swiftrtp.world.survival", w.permission());
        assertEquals(10000, w.area().maxRadius());
        assertEquals(500, w.area().minRadius());
        assertEquals(20, w.pool().size());
        assertEquals(3, w.pool().pregenRadius());
        assertEquals(Duration.ofMinutes(5), w.cooldown().base());
    }

    @Test
    void templateKeepsWorldPlaceholder() throws Exception {
        PluginSettings s = parse(yaml("""
                worlds:
                  defaults:
                    permission: "swiftrtp.world.{world}"
                  world: {}
                """));
        assertEquals("swiftrtp.world.{world}", s.defaults().permission());
        assertEquals("swiftrtp.world.world", s.worlds().get("world").permission());
    }

    @Test
    void invalidValuesAreClampedWithPathWarnings() throws Exception {
        PluginSettings s = parse(yaml("""
                worlds:
                  world:
                    area:
                      min-radius: 9000
                      max-radius: 100
                    pool:
                      size: 9999
                      keep-loaded-radius: 4
                      pregen-radius: 1
                    search:
                      max-attempts: 0
                      min-y: 200
                      max-y: 10
                      unsafe-floor: [lava, nonsense_block, "#minecraft:nope"]
                    teleport:
                      warmup: -3s
                      invulnerability: forever
                      commands: ["say hi", "player: spawn"]
                    cooldown:
                      tiers:
                        vip: 1m
                        "bad tier": 5s
                    cost:
                      items: ["diamond:2", {material: emerald, amount: 3}, "diamond", "unobtainium:1"]
                """));
        WorldSettings w = s.worlds().get("world");
        assertEquals(100, w.area().minRadius());
        assertEquals(9000, w.area().maxRadius());
        assertEquals(500, w.pool().size());
        assertEquals(4, w.pool().pregenRadius());
        assertEquals(1, w.search().maxAttempts());
        assertEquals(10, w.search().minY());
        assertEquals(200, w.search().maxY());
        assertEquals(Set.of(Material.LAVA), w.search().unsafeFloor());
        assertEquals(Duration.ZERO, w.teleport().warmup());
        assertEquals(Duration.ofSeconds(3), w.teleport().invulnerability());
        assertEquals(List.of("console: say hi", "player: spawn"), w.teleport().commands());
        assertEquals(Map.of("vip", Duration.ofMinutes(1)), w.cooldown().tiers()); // opaque: replaced, not merged
        assertEquals(List.of(new WorldSettings.ItemCost(Material.DIAMOND, 3), new WorldSettings.ItemCost(Material.EMERALD, 3)),
                w.cost().items());

        for (String expected : List.of("worlds.world.area", "worlds.world.pool.size", "worlds.world.pool.pregen-radius",
                "worlds.world.search.max-attempts", "worlds.world.search: min-y", "worlds.world.search.unsafe-floor",
                "worlds.world.teleport.warmup", "worlds.world.teleport.invulnerability",
                "worlds.world.teleport.commands", "worlds.world.cooldown.tiers.bad tier", "worlds.world.cost.items")) {
            assertTrue(warnings.stream().anyMatch(x -> x.startsWith(expected)), () -> expected + " in " + warnings);
        }
    }

    @Test
    void inheritedProblemsAreReportedOnceUnderDefaults() throws Exception {
        parse(yaml("""
                default-world: a
                worlds:
                  defaults:
                    pool:
                      size: -1
                  a: {}
                  b: {pool: {enabled: true}}
                """));
        assertEquals(List.of("worlds.defaults.pool.size: -1 is below the minimum 0; using 0"), warnings);
    }

    @Test
    void unknownKeysAreReportedAsTypos() throws Exception {
        parse(yaml("""
                debgu: true
                performance:
                  max-concurent-chunk-loads: 3
                worlds:
                  world:
                    area:
                      max-raduis: 100
                    cooldown:
                      tiers:
                        anything: 1s
                """));
        assertEquals(List.of(
                "debgu: unknown key (typo?); ignored",
                "performance.max-concurent-chunk-loads: unknown key (typo?); ignored",
                "worlds.world.area.max-raduis: unknown key (typo?); ignored"), warnings);
    }

    @Test
    void effectsAndSpecs() throws Exception {
        PluginSettings s = parse(yaml("""
                worlds:
                  world:
                    teleport:
                      effects:
                        warmup-tick-sound: ""
                        teleport-sound: "ENTITY.PLAYER.LEVELUP"
                        warmup-particles: {particle: nonexistent_particle}
                        arrival-particles: none
                        potion-effects:
                          - slow_falling
                          - {effect: "minecraft:speed", duration: 5s, amplifier: 1, particles: false}
                          - nonexistent_effect
                """));
        var e = s.worlds().get("world").teleport().effects();
        assertNull(e.warmupTickSound());
        assertEquals(new WorldSettings.SoundSpec("minecraft:entity.player.levelup", 1f, 1f), e.teleportSound());
        assertNull(e.warmupParticles());
        assertNull(e.arrivalParticles());
        assertEquals(List.of(
                new WorldSettings.PotionSpec("minecraft:slow_falling", Duration.ofSeconds(10), 0, false, true, true),
                new WorldSettings.PotionSpec("minecraft:speed", Duration.ofSeconds(5), 1, false, false, true)),
                e.potionEffects());
        assertEquals(2, warnings.size(), warnings::toString);
    }

    @Test
    void redirectsAliasesAndTriggers() throws Exception {
        PluginSettings s = parse(yaml("""
                default-world: ""
                world-redirects:
                  world_nether: world
                  loop: loop
                command-aliases: ["/Wild", "rtp", "bad alias", "wild"]
                triggers:
                  first-join: {enabled: true, world: "world", delay: 2s}
                """));
        assertNull(s.defaultWorld());
        assertEquals(Map.of("world_nether", "world"), s.worldRedirects());
        assertEquals(List.of("wild"), s.commandAliases());
        assertTrue(s.triggers().firstJoin().enabled());
        assertEquals("world", s.triggers().firstJoin().world());
        assertEquals(Duration.ofSeconds(2), s.triggers().firstJoin().delay());
        assertEquals(2, warnings.size(), warnings::toString); // self-redirect + bad alias
    }

    @Test
    void emptyWhitelistWarns() throws Exception {
        parse(yaml("""
                worlds:
                  world:
                    search:
                      biomes: {mode: whitelist, list: []}
                """));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("worlds.world.search.biomes.list"));
    }

    @Test
    void unlistedWorldsUseDefaults() throws Exception {
        PluginSettings s = parse(yaml("enable-unlisted-worlds: true\nworlds:\n  defaults: {}\n"));
        assertEquals(Map.of(), s.worlds());
        assertEquals("other", s.world("other").orElseThrow().worldName());
    }
}

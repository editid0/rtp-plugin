package io.github.editid0.swiftrtp.teleport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownTiersTest {

    private static Map<String, Duration> tiers() {
        Map<String, Duration> tiers = new LinkedHashMap<>();
        tiers.put("vip", Duration.ofSeconds(60));
        tiers.put("mvp", Duration.ofSeconds(30));
        tiers.put("slow", Duration.ofSeconds(600));
        return tiers;
    }

    @Test
    void noTierGivesBase() {
        assertEquals(Duration.ofSeconds(300), CooldownTiers.resolve(Duration.ofSeconds(300), tiers(), p -> false));
    }

    @Test
    void shortestHeldTierWins() {
        Set<String> perms = Set.of("swiftrtp.cooldown.vip", "swiftrtp.cooldown.mvp");
        assertEquals(Duration.ofSeconds(30), CooldownTiers.resolve(Duration.ofSeconds(300), tiers(), perms::contains));
    }

    @Test
    void longerTierNeverExtendsBase() {
        Set<String> perms = Set.of("swiftrtp.cooldown.slow");
        assertEquals(Duration.ofSeconds(300), CooldownTiers.resolve(Duration.ofSeconds(300), tiers(), perms::contains));
    }

    @Test
    void singleTier() {
        Set<String> perms = Set.of("swiftrtp.cooldown.vip");
        assertEquals(Duration.ofSeconds(60), CooldownTiers.resolve(Duration.ofSeconds(300), tiers(), perms::contains));
    }

    @Test
    void negativeValuesClampToZero() {
        assertEquals(Duration.ZERO, CooldownTiers.resolve(Duration.ofSeconds(-5), Map.of(), p -> true));
    }
}

package io.github.editid0.primedrtp.teleport;

import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pure cooldown tier resolution: the effective cooldown is the SHORTEST of the base cooldown and every tier whose
 * permission ({@code primedrtp.cooldown.<tier>}) the player has.
 */
public final class CooldownTiers {

    /** Permission prefix for cooldown tiers. */
    public static final String TIER_PERMISSION_PREFIX = "primedrtp.cooldown.";

    private CooldownTiers() {}

    /**
     * @param base          cooldown without any tier
     * @param tiers         tier name -> cooldown
     * @param hasPermission permission predicate (full permission node)
     * @return the shortest applicable cooldown, never negative
     */
    public static Duration resolve(Duration base, Map<String, Duration> tiers, Predicate<String> hasPermission) {
        Duration best = nonNegative(base);
        for (Map.Entry<String, Duration> tier : tiers.entrySet()) {
            Duration value = nonNegative(tier.getValue());
            if (value.compareTo(best) < 0 && hasPermission.test(TIER_PERMISSION_PREFIX + tier.getKey())) {
                best = value;
            }
        }
        return best;
    }

    private static Duration nonNegative(Duration d) {
        return d == null || d.isNegative() ? Duration.ZERO : d;
    }
}

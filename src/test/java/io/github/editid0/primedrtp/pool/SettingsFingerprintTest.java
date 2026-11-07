package io.github.editid0.primedrtp.pool;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SettingsFingerprintTest {

    @Test
    void stableForEqualSettingsAndIgnoresNonMaterialFields() {
        String a = SettingsFingerprint.of(TestSettings.world(5, Duration.ZERO, 0, 5000, 32));
        assertEquals(a, SettingsFingerprint.of(TestSettings.world(5, Duration.ZERO, 0, 5000, 32)));
        // pool size / spacing / expiry / max-attempts don't invalidate found locations
        assertEquals(a, SettingsFingerprint.of(TestSettings.world(9, Duration.ofMinutes(5), 100, 5000, 64)));
    }

    @Test
    void changesWithArea() {
        assertNotEquals(SettingsFingerprint.of(TestSettings.world(5, Duration.ZERO, 0, 5000, 32)),
                SettingsFingerprint.of(TestSettings.world(5, Duration.ZERO, 0, 6000, 32)));
    }
}

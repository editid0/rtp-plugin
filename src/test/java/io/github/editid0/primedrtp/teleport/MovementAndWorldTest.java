package io.github.editid0.primedrtp.teleport;

import io.github.editid0.primedrtp.teleport.TeleportOutcome.Status;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAndWorldTest {

    @Test
    void movementTolerance() {
        assertFalse(MovementCheck.exceeds(0, 64, 0, 0, 64, 0, 0));
        assertTrue(MovementCheck.exceeds(0, 64, 0, 0.01, 64, 0, 0));
        assertFalse(MovementCheck.exceeds(0, 64, 0, 0.5, 64, 0.5, 1.0));
        assertTrue(MovementCheck.exceeds(0, 64, 0, 1.0, 64, 1.0, 1.0));
        assertTrue(MovementCheck.exceeds(0, 64, 0, 0, 66, 0, 1.0));
        // Jumping in place never cancels, even with a tight tolerance; falling away does.
        assertFalse(MovementCheck.exceeds(0, 64, 0, 0, 65.25, 0, 0.5));
        assertTrue(MovementCheck.exceeds(0, 64, 0, 0, 62, 0, 0.5));
        assertFalse(MovementCheck.exceeds(0, 64, 0, 0, 64, 0, -3));
    }

    @Test
    void ticksAndSeconds() {
        assertEquals(0, MovementCheck.toTicks(0));
        assertEquals(1, MovementCheck.toTicks(1));
        assertEquals(100, MovementCheck.toTicks(5_000));
        assertEquals(5, MovementCheck.displaySeconds(100));
        assertEquals(5, MovementCheck.displaySeconds(81));
        assertEquals(4, MovementCheck.displaySeconds(80));
        assertEquals(1, MovementCheck.displaySeconds(1));
        assertEquals(0, MovementCheck.displaySeconds(0));
    }

    @Test
    void worldResolutionOrder() {
        Set<String> loaded = Set.of("world", "world_nether", "lobby", "resource");
        Set<String> enabled = Set.of("world", "resource");
        Map<String, String> redirects = Map.of("world_nether", "world", "lobby", "missing");

        assertEquals(Optional.of("world"), WorldResolution.resolve("world_nether", redirects, enabled::contains, "resource", loaded::contains));
        assertEquals(Optional.of("resource"), WorldResolution.resolve("resource", redirects, enabled::contains, "world", loaded::contains));
        // redirect target not loaded -> falls through to default-world
        assertEquals(Optional.of("resource"), WorldResolution.resolve("lobby", redirects, enabled::contains, "resource", loaded::contains));
        assertEquals(Optional.empty(), WorldResolution.resolve("lobby", redirects, enabled::contains, null, loaded::contains));
        assertEquals(Optional.empty(), WorldResolution.resolve("lobby", redirects, enabled::contains, "gone", loaded::contains));
    }

    @Test
    void reasonKeys() {
        assertEquals("teleport.reason.no-location-found", DefaultTeleportService.reasonKey(Status.NO_LOCATION_FOUND));
        assertEquals("teleport.reason.on-cooldown", DefaultTeleportService.reasonKey(Status.ON_COOLDOWN));
    }
}

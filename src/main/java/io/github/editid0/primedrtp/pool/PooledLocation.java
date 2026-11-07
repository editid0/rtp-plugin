package io.github.editid0.primedrtp.pool;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * A pre-validated, pre-generated location whose surrounding chunks are held loaded by the pool until
 * {@link PoolManager#release(PooledLocation)} is called.
 *
 * @param id        unique per server run
 * @param x,y,z     feet position (block centre for x/z)
 * @param createdAt epoch millis when it was found
 */
public record PooledLocation(long id, UUID worldId, String worldName, double x, double y, double z, float yaw,
                             long createdAt) {

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public int chunkX() {
        return blockX() >> 4;
    }

    public int chunkZ() {
        return blockZ() >> 4;
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, 0f);
    }
}

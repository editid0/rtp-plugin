package io.github.editid0.swiftrtp.search;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Immutable per-world state read on the global region thread (Folia-safe) and cached briefly by the searcher:
 * spawn, world border, player positions and world constants.
 *
 * @param players X/Z of every player in the world at capture time, packed via {@link SearchOptions#pack}
 */
record WorldSnapshot(long capturedAtNanos,
                     double spawnX, double spawnZ,
                     double borderCenterX, double borderCenterZ, double borderHalfSize,
                     long[] players,
                     World.Environment environment, int minHeight, int maxHeight, int logicalHeight, int seaLevel) {

    /** Must run on the global region thread (main thread on Paper). */
    static WorldSnapshot capture(World world) {
        Location spawn = world.getSpawnLocation();
        WorldBorder border = world.getWorldBorder();
        Location bc = border.getCenter();
        long[] players;
        try {
            List<Player> list = world.getPlayers();
            long[] tmp = new long[list.size()];
            int n = 0;
            for (Player p : list) {
                Location l = p.getLocation();
                if (n < tmp.length) {
                    tmp[n++] = SearchOptions.pack(l.getBlockX(), l.getBlockZ());
                }
            }
            players = n == tmp.length ? tmp : java.util.Arrays.copyOf(tmp, n);
        } catch (RuntimeException e) {
            players = new long[0];
        }
        return new WorldSnapshot(System.nanoTime(), spawn.getX(), spawn.getZ(), bc.getX(), bc.getZ(),
                border.getSize() / 2.0, players, world.getEnvironment(), world.getMinHeight(), world.getMaxHeight(),
                world.getLogicalHeight(), world.getSeaLevel());
    }

    long ageNanos(long now) {
        return now - capturedAtNanos;
    }
}

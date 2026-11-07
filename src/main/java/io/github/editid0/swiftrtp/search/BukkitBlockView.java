package io.github.editid0.swiftrtp.search;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * {@link BlockView} over a live world. Must be used on the thread owning {@code (chunkX, chunkZ)} with that chunk
 * loaded. Blocks in other chunks are only read if that chunk is loaded and owned by the current thread; otherwise
 * they behave as "unknown but harmless" (never triggers a synchronous chunk load).
 */
final class BukkitBlockView implements BlockView {

    private final World world;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final Set<Material> unsafeFloor;
    private final Set<Material> unsafeNearby;

    BukkitBlockView(World world, int chunkX, int chunkZ, Set<Material> unsafeFloor, Set<Material> unsafeNearby) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight() - 1;
        this.unsafeFloor = unsafeFloor;
        this.unsafeNearby = unsafeNearby;
    }

    private @Nullable Block block(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return null;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if ((cx != chunkX || cz != chunkZ)
                && !(Bukkit.isOwnedByCurrentRegion(world, cx, cz) && world.isChunkLoaded(cx, cz))) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public int surfaceY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    @Override
    public int skyY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
    }

    @Override
    public boolean solid(int x, int y, int z) {
        Block b = block(x, y, z);
        return b != null && b.isSolid() && !b.isLiquid();
    }

    @Override
    public boolean passable(int x, int y, int z) {
        Block b = block(x, y, z);
        return b == null || b.isPassable();
    }

    @Override
    public boolean liquid(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == null) {
            return false;
        }
        if (b.isLiquid()) {
            return true;
        }
        Material type = b.getType();
        if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
                || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS) {
            return true;
        }
        BlockData data = b.getBlockData();
        return data instanceof Waterlogged w && w.isWaterlogged();
    }

    @Override
    public boolean lava(int x, int y, int z) {
        Block b = block(x, y, z);
        return b != null && b.getType() == Material.LAVA;
    }

    @Override
    public boolean unsafeFloor(int x, int y, int z) {
        if (unsafeFloor.isEmpty()) {
            return false;
        }
        Block b = block(x, y, z);
        return b != null && unsafeFloor.contains(b.getType());
    }

    @Override
    public boolean unsafeNearby(int x, int y, int z) {
        if (unsafeNearby.isEmpty()) {
            return false;
        }
        Block b = block(x, y, z);
        return b != null && unsafeNearby.contains(b.getType());
    }
}

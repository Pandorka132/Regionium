package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Folia-style ownership/thread checks for Regionium. */
public final class RegioniumTickThread {
    private RegioniumTickThread() {}

    public static boolean isRegionThread() {
        return RegioniumContext.isRegionThread();
    }

    public static RegioniumWorldData getCurrentWorldData() {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data == null) {
            throw new IllegalStateException("No Regionium world context is active");
        }
        return data;
    }

    public static boolean isTickThreadFor(Level level, BlockPos pos) {
        return isTickThreadFor(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isTickThreadFor(Level level, ChunkPos pos) {
        return isTickThreadFor(level, pos.x(), pos.z());
    }

    public static boolean isTickThreadFor(Level level, Vec3 pos) {
        return isTickThreadFor(level, (int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4);
    }

    public static boolean isTickThreadFor(Level level, AABB box) {
        return isTickThreadFor(level,
            (int) Math.floor(box.minX) >> 4, (int) Math.floor(box.minZ) >> 4,
            (int) Math.floor(box.maxX) >> 4, (int) Math.floor(box.maxZ) >> 4);
    }

    public static boolean isTickThreadFor(Level level, BlockPos pos, int radius) {
        return isTickThreadFor(level,
            (pos.getX() - radius) >> 4, (pos.getZ() - radius) >> 4,
            (pos.getX() + radius) >> 4, (pos.getZ() + radius) >> 4);
    }

    public static boolean isTickThreadFor(Level level, int chunkX, int chunkZ) {
        if (!isRegionThread()) {
            return true;
        }
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return false;
        }
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && Regionium.scheduler().chunkLeases().owner(
            serverLevel, ChunkPos.pack(chunkX, chunkZ)
        ) == current;
    }

    public static boolean isTickThreadFor(Level level, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {
        for (int x = fromChunkX; x <= toChunkX; x++) {
            for (int z = fromChunkZ; z <= toChunkZ; z++) {
                if (!isTickThreadFor(level, x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isTickThreadFor(Entity entity) {
        if (entity == null || !isRegionThread()) {
            return true;
        }
        return isTickThreadFor(entity.level(), entity.chunkPosition());
    }
}

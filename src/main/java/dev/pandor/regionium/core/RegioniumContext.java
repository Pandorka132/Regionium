package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ServerCommonPacketListenerRegioniumAccess;
import dev.pandor.regionium.mixins.ServerGamePacketListenerRegioniumAccessorMixin;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Execution context of the currently ticking region.
 *
 * RegionizedWorldData is derived from the current region; it is not a second
 * global map keyed by region.
 */
public final class RegioniumContext {
    private static final ThreadLocal<RegioniumRegion> CURRENT_REGION = new ThreadLocal<>();

    private RegioniumContext() {
    }

    public static void enter(RegioniumRegion region) {
        CURRENT_REGION.set(Objects.requireNonNull(region, "region"));
    }

    public static void enterWorld(RegioniumWorldData data) {
        RegioniumRegion current = requireRegionThread();
        if (current.worldData() != data) {
            throw new IllegalStateException("World data does not belong to current region");
        }
    }

    public static void exitWorld() {
        // World data is derived from the current region. There is no second
        // ThreadLocal world-data state to clear.
    }

    public static void exit() {
        CURRENT_REGION.remove();
    }

    public static @Nullable RegioniumWorldData currentWorldData() {
        RegioniumRegion region = CURRENT_REGION.get();
        return region == null ? null : region.worldData();
    }

    public static @Nullable RegioniumRegion currentRegion() {
        return CURRENT_REGION.get();
    }

    public static boolean isRegionThread() {
        return CURRENT_REGION.get() != null;
    }

    public static RegioniumRegion requireRegionThread() {
        RegioniumRegion region = CURRENT_REGION.get();
        if (region == null) {
            throw new IllegalStateException(
                "This operation requires a Regionium region context"
            );
        }
        return region;
    }

    /**
     * Mirrors Folia's TickThread.isTickThreadFor(Entity) for Regionium's
     * region workers and the vanilla global server thread. The global thread
     * may access non-player entities, but a PLAY player connection belongs to
     * its region and is not globally tick-owned.
     */
    public static boolean isCurrentThreadFor(Entity entity) {
        if (entity == null) {
            return true;
        }

        RegioniumRegion region = CURRENT_REGION.get();
        if (region != null) {
            return entity.level() == region.level()
                && Regionium.scheduler().ownerOf(entity) == region;
        }

        MinecraftServer server = Regionium.scheduler().server();
        if (server == null || !server.isSameThread()) {
            return false;
        }

        if (entity instanceof ServerPlayer player) {
            if (player.connection == null) {
                return true;
            }

            Connection connection = ((ServerCommonPacketListenerRegioniumAccess) (Object) player.connection)
                .regionium$getConnection();
            PacketListener listener = connection.getPacketListener();
            if (listener instanceof ServerGamePacketListenerImpl game) {
                return ((ServerGamePacketListenerRegioniumAccessorMixin) (Object) game)
                    .regionium$isWaitingForSwitchToConfig();
            }
            if (listener instanceof ServerConfigurationPacketListenerImpl) {
                // Fabric 26.3 has no Folia switchToMain state on this listener.
                return false;
            }
        }

        return true;
    }

    /**
     * Folia's projectile movement guard: a projectile may tick only while the
     * region owning it also owns the chunk neighbourhood swept by its next
     * movement. This is a no-load ownership check.
     */
    public static boolean canMoveWithinCurrentRegion(Entity entity) {
        RegioniumRegion current = CURRENT_REGION.get();
        if (current == null) {
            return true;
        }
        if (entity.level() != current.level()
            || Regionium.scheduler().ownerOf(entity) != current) {
            return false;
        }

        Vec3 start = entity.position();
        Vec3 delta = entity.getDeltaMovement();
        double minX = Math.min(start.x, start.x + delta.x) - 16.0;
        double maxX = Math.max(start.x, start.x + delta.x) + 16.0;
        double minZ = Math.min(start.z, start.z + delta.z) - 16.0;
        double maxZ = Math.max(start.z, start.z + delta.z) + 16.0;
        int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(minX));
        int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(maxX));
        int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(minZ));
        int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(maxZ));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                BlockPos probe = new BlockPos(chunkX << 4, current.level().getMinY(), chunkZ << 4);
                if (!Regionium.scheduler().isCurrentRegionFor(current.level(), probe)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean ownsBlockNeighborhood(net.minecraft.world.level.Level level, BlockPos pos, int radiusBlocks) {
        RegioniumRegion current = CURRENT_REGION.get();
        if (current == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return true;
        }
        if (current.level() != serverLevel) {
            return false;
        }
        int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX() - radiusBlocks);
        int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX() + radiusBlocks);
        int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ() - radiusBlocks);
        int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ() + radiusBlocks);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                BlockPos probe = new BlockPos(chunkX << 4, serverLevel.getMinY(), chunkZ << 4);
                if (!Regionium.scheduler().isCurrentRegionFor(serverLevel, probe)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static @Nullable net.minecraft.world.level.block.state.BlockState getBlockStateIfOwnedAndLoaded(
        net.minecraft.world.level.LevelAccessor level, BlockPos pos, int radiusBlocks
    ) {
        if (level instanceof net.minecraft.world.level.Level actualLevel) {
            return getBlockStateIfOwnedAndLoaded(actualLevel, pos, radiusBlocks);
        }
        return level.getBlockState(pos);
    }

    public static @Nullable net.minecraft.world.level.block.state.BlockState getBlockStateIfOwnedAndLoaded(
        net.minecraft.world.level.Level level, BlockPos pos, int radiusBlocks
    ) {
        if (!ownsBlockNeighborhood(level, pos, radiusBlocks)) {
            return null;
        }
        if (RegioniumContext.isRegionThread() && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.chunk.ChunkAccess chunk = RegioniumPortalChunkAccess.getLoadedChunk(
                serverLevel, pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL
            );
            return chunk == null ? null : chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }

    public static long redstoneTime(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            RegioniumRegion current = CURRENT_REGION.get();
            if (current != null && current.level() == serverLevel
                && Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)) {
                return current.worldData().redstoneTime();
            }
        }
        return level.getGameTime();
    }

    public static void requireOwner(Object object) {
        RegioniumRegion current = requireRegionThread();
        RegioniumRegion owner = Regionium.scheduler().ownerOf(object);
        if (owner != current) {
            throw new IllegalStateException(
                "Illegal cross-region access: current=" + current +
                    ", owner=" + owner +
                    ", object=" + object
            );
        }
    }
}

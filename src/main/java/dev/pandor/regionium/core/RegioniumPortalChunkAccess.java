package dev.pandor.regionium.core;

import dev.pandor.regionium.mixins.ChunkMapRegioniumInvokerMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class RegioniumPortalChunkAccess {
    private RegioniumPortalChunkAccess() {
    }

    @Nullable
    public static ChunkAccess getLoadedChunk(Level level, int chunkX, int chunkZ, ChunkStatus minimumStatus) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
        ChunkHolder holder = ((ChunkMapRegioniumInvokerMixin) (Object) chunkMap)
            .regionium$getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        return holder == null ? null : holder.getChunkIfPresent(minimumStatus);
    }

    public static CompletableFuture<Optional<BlockPos>> findClosestPortalPositionAsync(
        ServerLevel level, BlockPos center, boolean toNether, WorldBorder worldBorder
    ) {
        int radius = toNether ? 16 : 128;
        int chunkRadius = Math.floorDiv(radius, 16) + 1;
        ChunkPos centerChunk = ChunkPos.containing(center);
        PoiManager poiManager = level.getPoiManager();
        List<CompletableFuture<?>> poiLoads = new ArrayList<>();
        for (int x = centerChunk.x() - chunkRadius; x <= centerChunk.x() + chunkRadius; x++) {
            for (int z = centerChunk.z() - chunkRadius; z <= centerChunk.z() + chunkRadius; z++) {
                poiLoads.add(poiManager.prefetch(new ChunkPos(x, z)));
            }
        }
        CompletableFuture<?>[] all = poiLoads.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all).thenCompose(ignored -> {
            // Vanilla SectionStorage/PoiManager is not concurrent. All POI
            // chunks are prefetched above; query the index on the server thread
            // rather than racing POI writes from a ForkJoin worker.
            CompletableFuture<Optional<BlockPos>> result = new CompletableFuture<>();
            level.getServer().execute(() -> {
                try {
                    // Vanilla validates POI sections against block sections here.
                    // The complete search square already has EMPTY chunk tickets,
                    // so this validation can no longer synchronously load an
                    // unprepared chunk from a region worker.
                    poiManager.ensureLoadedAndValid(level, center, radius);
                    result.complete(poiManager.getInSquare(
                            type -> type.is(PoiTypes.NETHER_PORTAL), center, radius, PoiManager.Occupancy.ANY
                        )
                        .map(PoiRecord::getPos)
                        .filter(worldBorder::isWithinBounds)
                        .filter(pos -> {
                            ChunkAccess chunk = getLoadedChunk(
                                level, pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.EMPTY
                            );
                            if (chunk == null) {
                                throw new IllegalStateException("Portal POI search touched an unprepared chunk at " + pos);
                            }
                            BlockState state = chunk.getBlockState(pos);
                            return state.is(Blocks.NETHER_PORTAL)
                                && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS);
                        })
                        .min(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(center))
                            .thenComparingInt(Vec3i::getY)));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        });
    }

    public static BlockState getBlockState(Level level, BlockPos pos, ChunkStatus minimumStatus) {
        ChunkAccess chunk = getLoadedChunk(level, pos.getX() >> 4, pos.getZ() >> 4, minimumStatus);
        if (chunk == null) {
            throw new IllegalStateException(
                "Portal operation accessed an unprepared chunk at " + pos + " in " + level.dimension()
            );
        }
        return chunk.getBlockState(pos);
    }

    public static int getHeight(Level level, Heightmap.Types type, int x, int z) {
        ChunkAccess chunk = getLoadedChunk(level, x >> 4, z >> 4, ChunkStatus.FULL);
        if (chunk == null) {
            throw new IllegalStateException(
                "Portal creation accessed an unprepared heightmap at " + x + "," + z + " in " + level.dimension()
            );
        }
        return chunk.getHeight(type, x & 15, z & 15);
    }
}

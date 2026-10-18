package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(PortalForcer.class)
public abstract class PortalForcerRegioniumMixin {
    @Redirect(
        method = "canPortalReplaceBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState regionium$readLoadedReplaceCandidate(ServerLevel level, BlockPos pos) {
        var chunk = RegioniumPortalChunkAccess.getLoadedChunk(
            level, pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL
        );
        if (chunk == null) {
            throw new IllegalStateException("Portal creation tried to inspect an unprepared chunk at " + pos);
        }
        return chunk.getBlockState(pos);
    }

    @Redirect(
        method = "canHostFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState regionium$readLoadedFrameCandidate(ServerLevel level, BlockPos pos) {
        var chunk = RegioniumPortalChunkAccess.getLoadedChunk(
            level, pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL
        );
        if (chunk == null) {
            throw new IllegalStateException("Portal creation tried to inspect an unprepared chunk at " + pos);
        }
        return chunk.getBlockState(pos);
    }

    @Redirect(
        method = "createPortal",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"
        )
    )
    private int regionium$readLoadedHeightmap(ServerLevel level, Heightmap.Types type, int x, int z) {
        var chunk = RegioniumPortalChunkAccess.getLoadedChunk(level, x >> 4, z >> 4, ChunkStatus.FULL);
        if (chunk == null) {
            throw new IllegalStateException("Portal creation tried to inspect an unprepared heightmap at " + x + "," + z);
        }
        return chunk.getHeight(type, x & 15, z & 15);
    }
}

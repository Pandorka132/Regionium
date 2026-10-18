package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shape updates are suppressed when their destination is outside this region or unloaded. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$ShapeUpdate")
abstract class CollectingNeighborUpdaterShapeRegioniumMixin {
    @Redirect(
        method = "runNext",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeShapeUpdate(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)V")
    )
    private void regionium$skipForeignOrUnloadedShapeUpdate(
        LevelAccessor level, Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState,
        int updateFlags, int updateLimit
    ) {
        if (level instanceof ServerLevel serverLevel) {
            var current = RegioniumContext.currentRegion();
            long chunkKey = ChunkPos.pack(pos);
            if (current != null
                && (!Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)
                    || (!current.worldData().hasChunk(chunkKey)
                        && !Regionium.scheduler().regionizer().hasLoadedFullChunk(serverLevel, chunkKey)))) {
        
                return;
            }
        }
        NeighborUpdater.executeShapeUpdate(level, direction, pos, neighborPos, neighborState, updateFlags, updateLimit);
    }
}

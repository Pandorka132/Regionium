package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Folia's boundary rule: a multi-neighbor update never reads a foreign or unloaded chunk. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate")
abstract class CollectingNeighborUpdaterMultiRegioniumMixin {
    @Redirect(
        method = "runNext",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState regionium$readOnlyOwnedLoadedNeighbor(Level level, BlockPos pos) {

        if (level instanceof ServerLevel serverLevel) {
            var current = RegioniumContext.currentRegion();
            long chunkKey = ChunkPos.pack(pos);
            if (current != null
                && (!Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)
                    || (!current.worldData().hasChunk(chunkKey)
                        && !Regionium.scheduler().regionizer().hasLoadedFullChunk(serverLevel, chunkKey)))) {
        
                return null;
            }
        }
        BlockState state = level.getBlockState(pos);

        return state;
    }

    @Redirect(
        method = "runNext",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V")
    )
    private void regionium$skipForeignOrUnloadedNeighbor(
        Level level, BlockState state, BlockPos pos, Block sourceBlock, Orientation orientation, boolean movedByPiston
    ) {

        if (state != null) {
            NeighborUpdater.executeUpdate(level, state, pos, sourceBlock, orientation, movedByPiston);
        }
    }
}

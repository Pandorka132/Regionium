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

/** Keeps simple neighbor notifications inside the owning region and loaded chunks. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$SimpleNeighborUpdate")
abstract class CollectingNeighborUpdaterSimpleRegioniumMixin {
    @Redirect(
        method = "runNext",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState regionium$readOnlyOwnedLoadedNeighbor(Level level, BlockPos pos) {
        BlockState state = RegioniumContext.getBlockStateIfOwnedAndLoaded(level, pos, 25);
        if (pos.getX() >= 17650 && pos.getX() <= 17680) {
            Regionium.LOGGER.info("[PACKET-REDSTONE-DIAG] simple read pos={} state={} current={}", pos, state, RegioniumContext.currentRegion());
        }
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

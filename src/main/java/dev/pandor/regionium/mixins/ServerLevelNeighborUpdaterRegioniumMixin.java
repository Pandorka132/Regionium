package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Folia-style: the mutable neighbor-update state belongs to the region. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelNeighborUpdaterRegioniumMixin {
    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$updateNeighborsAtOriented(BlockPos pos, Block block, Orientation orientation, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.requireRegionThread();
        updater((ServerLevel)(Object)this, region).updateNeighborsAtExceptFromFacing(pos, block, null, orientation);
        ci.cancel();
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void regionium$updateNeighborsAtExcept(BlockPos pos, Block block, Direction skipDirection, Orientation orientation, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.requireRegionThread();
        updater((ServerLevel)(Object)this, region).updateNeighborsAtExceptFromFacing(pos, block, skipDirection, orientation);
        ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$neighborChanged(BlockPos pos, Block changedBlock, Orientation orientation, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.requireRegionThread();
        updater((ServerLevel)(Object)this, region).neighborChanged(pos, changedBlock, orientation);
        ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V", at = @At("HEAD"), cancellable = true)
    private void regionium$neighborChangedState(BlockState state, BlockPos pos, Block changedBlock, Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.requireRegionThread();
        updater((ServerLevel)(Object)this, region).neighborChanged(state, pos, changedBlock, orientation, movedByPiston);
        ci.cancel();
    }

    private static NeighborUpdater updater(ServerLevel level, dev.pandor.regionium.core.RegioniumRegion region) {
        return Regionium.scheduler().worldData(level).neighborUpdater(region);
    }
}

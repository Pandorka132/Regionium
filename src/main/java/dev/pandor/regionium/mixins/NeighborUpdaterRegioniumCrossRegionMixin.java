package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dispatches ordinary neighbor callbacks to the region that owns the target block. */
@Mixin(NeighborUpdater.class)
interface NeighborUpdaterRegioniumCrossRegionMixin {
    @Inject(method = "executeUpdate", at = @At("HEAD"), cancellable = true)
    private static void regionium$routeNeighborCallback(
        Level level,
        BlockState state,
        BlockPos pos,
        Block block,
        Orientation orientation,
        boolean movedByPiston,
        CallbackInfo ci
    ) {
        if (!(level instanceof ServerLevel serverLevel) || !RegioniumContext.isRegionThread()) {
            return;
        }

        RegioniumRegion current = RegioniumContext.requireRegionThread();
        long targetChunk = net.minecraft.world.level.ChunkPos.pack(pos);
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(serverLevel, targetChunk);
        if (owner == current && current.worldData().hasChunk(targetChunk)) {
            return;
        }

        Regionium.scheduler().dispatchNeighborUpdate(serverLevel, targetChunk, () ->
            NeighborUpdater.executeUpdate(level, state, pos, block, orientation, movedByPiston)
        );
        ci.cancel();
    }

}

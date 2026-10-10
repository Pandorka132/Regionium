package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs cross-region neighbor-shape work on the region that owns the block
 * whose shape is being updated. A synchronous foreign-chunk read can abort a
 * piston block-entity tick halfway through its state transition.
 */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$ShapeUpdate")
abstract class CollectingNeighborUpdaterRegioniumShapeRoutingMixin {
    @Shadow @Final private Direction direction;
    @Shadow @Final private BlockState neighborState;
    @Shadow @Final private BlockPos pos;
    @Shadow @Final private BlockPos neighborPos;
    @Shadow @Final private int updateFlags;
    @Shadow @Final private int updateLimit;

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$routeForeignShapeUpdate(
        Level level,
        CallbackInfoReturnable<Boolean> cir
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

        // The update belongs to the chunk at pos. Do not discard it when the
        // owner disappears between scheduling and execution: Regionium keeps
        // it pending until that chunk is registered again.
        Regionium.scheduler().dispatchNeighborUpdate(serverLevel, targetChunk, () ->
            NeighborUpdater.executeShapeUpdate(
                serverLevel, direction, pos, neighborPos, neighborState, updateFlags, updateLimit
            )
        );
        cir.setReturnValue(false);
    }

}

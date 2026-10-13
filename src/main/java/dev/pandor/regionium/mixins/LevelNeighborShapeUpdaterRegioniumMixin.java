package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Folia-style: shape updates use the current region's neighbor updater. */
@Mixin(Level.class)
abstract class LevelNeighborShapeUpdaterRegioniumMixin {
    @Inject(method = "neighborShapeChanged", at = @At("HEAD"), cancellable = true)
    private void regionium$useRegionNeighborUpdater(
        Direction direction,
        BlockPos pos,
        BlockPos neighborPos,
        BlockState neighborState,
        int updateFlags,
        int updateLimit,
        CallbackInfo ci
    ) {
        if (!((Object) this instanceof ServerLevel level) || !RegioniumContext.isRegionThread()) {
            return;
        }

        var region = RegioniumContext.requireRegionThread();
        region.worldData().neighborUpdater().shapeUpdate(
            direction, neighborState, pos, neighborPos, updateFlags, updateLimit
        );
        ci.cancel();
    }
}

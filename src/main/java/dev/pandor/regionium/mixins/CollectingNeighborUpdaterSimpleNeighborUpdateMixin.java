package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes SimpleNeighborUpdate before its vanilla target-state read. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$SimpleNeighborUpdate")
public abstract class CollectingNeighborUpdaterSimpleNeighborUpdateMixin {
    @Shadow @Final private BlockPos pos;
    @Shadow @Final private Block block;
    @Shadow @Final private Orientation orientation;

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$routeBeforeTargetRead(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel) || !RegioniumContext.isRegionThread()) {
            return;
        }

        BlockPos targetPos = pos.immutable();
        long targetChunk = net.minecraft.world.level.ChunkPos.pack(targetPos);
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(serverLevel, targetChunk);
        if (owner == current && regionium$isFull(serverLevel, targetChunk)) {
            return;
        }

        Regionium.scheduler().dispatchNeighborUpdate(serverLevel, targetChunk, () -> {
            BlockState state = serverLevel.getBlockState(targetPos);
            NeighborUpdater.executeUpdate(serverLevel, state, targetPos, block, orientation, false);
        });
        cir.setReturnValue(false);
    }

    private static boolean regionium$isFull(ServerLevel level, long chunkPos) {
        var visible = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) level.getChunkSource().chunkMap)
            .regionium$getVisibleChunkMap();
        ChunkHolder holder = visible.get(chunkPos);
        return holder != null && holder.getChunkIfPresent(ChunkStatus.FULL) != null;
    }
}

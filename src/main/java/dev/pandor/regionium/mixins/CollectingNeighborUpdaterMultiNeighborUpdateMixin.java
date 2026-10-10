package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes a multi-neighbor callback before vanilla reads the target block.
 * Routing only NeighborUpdater.executeUpdate is too late: vanilla's
 * MultiNeighborUpdate.runNext first calls Level.getBlockState on the target,
 * which can synchronously load a chunk owned by another region. Advance the
 * direction cursor here and execute the callback on the target chunk's owner
 * once that chunk is FULL.
 */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate")
public abstract class CollectingNeighborUpdaterMultiNeighborUpdateMixin {
    @Shadow @Final private BlockPos sourcePos;
    @Shadow @Final private Block sourceBlock;
    @Shadow private Orientation orientation;
    @Shadow @Final private Direction skipDirection;
    @Shadow private int idx;

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$routeBeforeTargetRead(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel) || !RegioniumContext.isRegionThread()) {
            return;
        }

        Direction[] order = NeighborUpdater.UPDATE_ORDER;
        if (idx >= order.length) {
            cir.setReturnValue(false);
            return;
        }

        Direction direction = order[idx];
        BlockPos targetPos = sourcePos.relative(direction).immutable();
        long targetChunk = net.minecraft.world.level.ChunkPos.pack(targetPos);
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(serverLevel, targetChunk);
        if (owner == current && regionium$isFull(serverLevel, targetChunk)) {
            return;
        }

        Orientation callbackOrientation = null;
        if (level.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS)) {
            if (orientation == null) {
                orientation = ExperimentalRedstoneUtils.initialOrientation(
                    level,
                    skipDirection == null ? null : skipDirection.getOpposite(),
                    null
                );
            }
            callbackOrientation = orientation.withFront(direction);
        }

        Orientation finalOrientation = callbackOrientation;
        Regionium.scheduler().dispatchNeighborUpdate(serverLevel, targetChunk, () -> {
            BlockState state = serverLevel.getBlockState(targetPos);
            NeighborUpdater.executeUpdate(
                serverLevel, state, targetPos, sourceBlock, finalOrientation, false
            );
        });

        // Match vanilla's cursor advancement, including its skipped face.
        idx++;
        if (idx < order.length && order[idx] == skipDirection) {
            idx++;
        }
        cir.setReturnValue(idx < order.length);
    }

    private static boolean regionium$isFull(ServerLevel level, long chunkPos) {
        var visible = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) level.getChunkSource().chunkMap)
            .regionium$getVisibleChunkMap();
        ChunkHolder holder = visible.get(chunkPos);
        return holder != null && holder.getChunkIfPresent(ChunkStatus.FULL) != null;
    }
}

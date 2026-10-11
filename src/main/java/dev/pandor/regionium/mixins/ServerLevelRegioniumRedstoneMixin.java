package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.ArrayList;
import java.util.List;

@Mixin(ServerLevel.class)
public abstract class ServerLevelRegioniumRedstoneMixin {
    @Inject(
        method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$updateNeighborsAt(BlockPos pos, Block block, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().neighborUpdater().updateNeighborsAtExceptFromFacing(
            pos,
            block,
            null,
            ExperimentalRedstoneUtils.initialOrientation(level, null, null)
        );
        ci.cancel();
    }

    @Inject(
        method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$updateNeighborsAtWithOrientation(
        BlockPos pos,
        Block block,
        Orientation orientation,
        CallbackInfo ci
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().neighborUpdater().updateNeighborsAtExceptFromFacing(
            pos, block, null, orientation
        );
        ci.cancel();
    }

    @Inject(
        method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/redstone/Orientation;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$updateNeighborsAtExceptFromFacing(
        BlockPos pos,
        Block block,
        Direction skipDirection,
        Orientation orientation,
        CallbackInfo ci
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().neighborUpdater().updateNeighborsAtExceptFromFacing(
            pos, block, skipDirection, orientation
        );
        ci.cancel();
    }

    @Inject(
        method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$neighborChanged(
        BlockPos pos,
        Block changedBlock,
        Orientation orientation,
        CallbackInfo ci
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().neighborUpdater().neighborChanged(pos, changedBlock, orientation);
        ci.cancel();
    }

    @Inject(
        method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$neighborChangedState(
        BlockState state,
        BlockPos pos,
        Block changedBlock,
        Orientation orientation,
        boolean movedByPiston,
        CallbackInfo ci
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().neighborUpdater().neighborChanged(
            state, pos, changedBlock, orientation, movedByPiston
        );
        ci.cancel();
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void regionium$queueRegionBlockEvent(
        BlockPos pos,
        Block block,
        int paramA,
        int paramB,
        CallbackInfo ci
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        if (!Regionium.scheduler().isCurrentRegionFor(level, pos)) {
            throw new IllegalStateException(
                "Cannot queue block event outside its owning region: " + pos
            );
        }

        current.worldData().pushBlockEvent(new BlockEventData(pos, block, paramA, paramB));
        ci.cancel();
    }

    @Inject(method = "runBlockEvents", at = @At("HEAD"), cancellable = true)
    private void regionium$runRegionBlockEvents(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        RegioniumWorldData data = current.worldData();
        List<BlockEventData> reschedule = new ArrayList<>(64);
        BlockEventData event;
        while ((event = data.removeFirstBlockEvent()) != null) {
            if (level.shouldTickBlocksAt(event.pos())) {
                if (regionium$doBlockEvent(event)) {
                    level.getServer().getPlayerList().broadcast(
                        null,
                        event.pos().getX(),
                        event.pos().getY(),
                        event.pos().getZ(),
                        64.0,
                        level.dimension(),
                        new ClientboundBlockEventPacket(
                            event.pos(),
                            event.block(),
                            event.paramA(),
                            event.paramB()
                        )
                    );
                }
            } else {
                reschedule.add(event);
            }
        }

        data.pushBlockEvents(reschedule);
        ci.cancel();
    }

    @Inject(method = "clearBlockEvents", at = @At("HEAD"), cancellable = true)
    private void regionium$clearRegionBlockEvents(BoundingBox box, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            return;
        }

        current.worldData().removeBlockEvents(event -> box.isInside(event.pos()));
        ci.cancel();
    }

    @Invoker("doBlockEvent")
    protected abstract boolean regionium$doBlockEvent(BlockEventData event);
}

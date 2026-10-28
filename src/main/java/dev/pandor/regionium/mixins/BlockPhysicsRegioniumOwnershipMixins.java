package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailState;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(DetectorRailBlock.class)
abstract class DetectorRailBlockRegioniumOwnershipMixin {
    @Overwrite
    protected void updatePowerToConnected(Level level, BlockPos pos, BlockState state, boolean powered) {
        RailState rail = new RailState(level, pos, state);
        for (BlockPos connectionPos : rail.getConnections()) {
            BlockState connectionState = RegioniumContext.getBlockStateIfOwnedAndLoaded(level, connectionPos, 25);
            if (connectionState != null) {
                level.neighborChanged(connectionState, connectionPos, connectionState.getBlock(), null, false);
            }
        }
    }
}

@Mixin(PoweredRailBlock.class)
abstract class PoweredRailBlockRegioniumOwnershipMixin {
    @Invoker("findPoweredRailSignal")
    protected abstract boolean regionium$findPoweredRailSignal(Level level, BlockPos pos, BlockState state, boolean forward, int searchDepth);

    @Overwrite
    protected boolean isSameRailWithPower(Level level, BlockPos pos, boolean forward, int searchDepth, RailShape dir) {
        BlockState state = RegioniumContext.getBlockStateIfOwnedAndLoaded(level, pos, 25);
        if (state == null || !state.is((PoweredRailBlock) (Object) this)) {
            return false;
        }

        RailShape myShape = state.getValue(PoweredRailBlock.SHAPE);
        if (dir != RailShape.EAST_WEST || myShape != RailShape.NORTH_SOUTH && myShape != RailShape.ASCENDING_NORTH && myShape != RailShape.ASCENDING_SOUTH) {
            if (dir != RailShape.NORTH_SOUTH || myShape != RailShape.EAST_WEST && myShape != RailShape.ASCENDING_EAST && myShape != RailShape.ASCENDING_WEST) {
                if (!state.getValue(PoweredRailBlock.POWERED)) {
                    return false;
                }
                return level.hasNeighborSignal(pos)
                    || this.regionium$findPoweredRailSignal(level, pos, state, forward, searchDepth + 1);
            }
            return false;
        }
        return false;
    }
}

@Mixin(TripWireBlock.class)
abstract class TripWireBlockRegioniumOwnershipMixin {
    @Shadow @Final private Block hook;

    @Overwrite
    private void updateSource(Level level, BlockPos pos, BlockState state) {
        for (Direction direction : new Direction[]{Direction.SOUTH, Direction.WEST}) {
            for (int i = 1; i < 42; i++) {
                BlockPos testPos = pos.relative(direction, i);
                if (!RegioniumContext.ownsBlockNeighborhood(level, testPos, 4)) {
                    break;
                }
                BlockState block = level.getBlockState(testPos);
                if (block.is(this.hook)) {
                    if (block.getValue(TripWireHookBlock.FACING) == direction.getOpposite()) {
                        TripWireHookBlock.calculateState(level, testPos, block, false, true, i, state);
                    }
                    break;
                }
                if (!block.is((TripWireBlock) (Object) this)) {
                    break;
                }
            }
        }
    }
}

@Mixin(TripWireHookBlock.class)
abstract class TripWireHookBlockRegioniumOwnershipMixin {
    @Inject(method = "calculateState", at = @At("HEAD"), cancellable = true)
    private static void regionium$stopAtForeignRegion(
        Level level,
        BlockPos pos,
        BlockState state,
        boolean isBeingDestroyed,
        boolean canUpdate,
        int wireSource,
        BlockState wireSourceState,
        CallbackInfo ci
    ) {
        Optional<Direction> facing = state.getOptionalValue(TripWireHookBlock.FACING);
        if (facing.isEmpty() || RegioniumContext.currentRegion() == null) {
            return;
        }

        Direction direction = facing.get();
        for (int i = 1; i < 42; i++) {
            BlockPos testPos = pos.relative(direction, i);
            if (!RegioniumContext.ownsBlockNeighborhood(level, testPos, 4)) {
                ci.cancel();
                return;
            }
            BlockState wireState = level.getBlockState(testPos);
            if (wireState.is(Blocks.TRIPWIRE_HOOK)) {
                break;
            }
        }
    }
}

package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.RedstoneWireBlockRegioniumAccess;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import java.util.Map;
import org.spongepowered.asm.mixin.Overwrite;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneWireBlockRegioniumMixin implements RedstoneWireBlockRegioniumAccess {
    @Shadow private boolean shouldSignal;

    @Unique
    @Override
    public boolean regionium$getShouldSignal() {
        return this.shouldSignal;
    }

    @Unique
    @Override
    public void regionium$setShouldSignal(boolean shouldSignal) {
        this.shouldSignal = shouldSignal;
    }

    @Overwrite
    protected void updateIndirectNeighbourShapes(
        BlockState state, LevelAccessor level, BlockPos pos, int updateFlags, int updateLimit
    ) {
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            RedstoneSide side = state.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(direction));
            blockPos.setWithOffset(pos, direction);
            BlockState currentState = RegioniumContext.getBlockStateIfOwnedAndLoaded(level, blockPos, 25);
            if (side != RedstoneSide.NONE && currentState != null && !currentState.is((RedstoneWireBlock) (Object) this)) {
                blockPos.move(Direction.DOWN);
                BlockState blockStateDown = level.getBlockState(blockPos);
                if (blockStateDown.is((RedstoneWireBlock) (Object) this)) {
                    BlockPos neighborPos = blockPos.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), blockPos, neighborPos, level.getBlockState(neighborPos), updateFlags, updateLimit);
                }

                blockPos.setWithOffset(pos, direction).move(Direction.UP);
                BlockState blockStateUp = level.getBlockState(blockPos);
                if (blockStateUp.is((RedstoneWireBlock) (Object) this)) {
                    BlockPos neighborPos = blockPos.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), blockPos, neighborPos, level.getBlockState(neighborPos), updateFlags, updateLimit);
                }
            }
        }
    }

    @Redirect(
        method = "getBlockSignal",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z", opcode = Opcodes.PUTFIELD)
    )
    private void regionium$setRegionSignalSuppression(RedstoneWireBlock wire, boolean value) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data == null) {
            ((RedstoneWireBlockRegioniumAccess) (Object) wire).regionium$setShouldSignal(value);
        } else {
            data.shouldSignal = value;
        }
    }

    @Redirect(
        method = "getDirectSignal",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z", opcode = Opcodes.GETFIELD)
    )
    private boolean regionium$getDirectSignalSuppression(RedstoneWireBlock wire) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        return data == null ? ((RedstoneWireBlockRegioniumAccess) (Object) wire).regionium$getShouldSignal() : data.shouldSignal;
    }

    @Redirect(
        method = "getSignal",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z", opcode = Opcodes.GETFIELD)
    )
    private boolean regionium$getSignalSuppression(RedstoneWireBlock wire) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        return data == null ? ((RedstoneWireBlockRegioniumAccess) (Object) wire).regionium$getShouldSignal() : data.shouldSignal;
    }

    @Redirect(
        method = "isSignalSource",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/RedstoneWireBlock;shouldSignal:Z", opcode = Opcodes.GETFIELD)
    )
    private boolean regionium$isSignalSourceSuppressed(RedstoneWireBlock wire) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        return data == null ? ((RedstoneWireBlockRegioniumAccess) (Object) wire).regionium$getShouldSignal() : data.shouldSignal;
    }
}

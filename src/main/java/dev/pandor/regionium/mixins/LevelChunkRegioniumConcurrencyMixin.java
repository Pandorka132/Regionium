package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Gives each live chunk a small ownership-independent monitor for the two
 * fundamental block-state operations. Different chunks can still execute in
 * parallel; only simultaneous access to the same chunk is serialized.
 *
 * This is intentionally narrower than a world-wide lock: redstone/piston
 * activity crossing a region boundary can briefly contend on the boundary
 * chunk without stopping unrelated regions.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRegioniumConcurrencyMixin {
    @WrapMethod(method = "getBlockState")
    private BlockState regionium$lockedBlockRead(
        BlockPos pos,
        Operation<BlockState> original
    ) {
        synchronized (this) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "setBlockState")
    private BlockState regionium$lockedBlockWrite(
        BlockPos pos,
        BlockState state,
        int flags,
        Operation<BlockState> original
    ) {
        synchronized (this) {
            return original.call(pos, state, flags);
        }
    }

    @WrapMethod(method = "getBlockEntity")
    private net.minecraft.world.level.block.entity.BlockEntity regionium$lockedBlockEntityRead(
        BlockPos pos,
        Operation<net.minecraft.world.level.block.entity.BlockEntity> original
    ) {
        synchronized (this) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;")
    private net.minecraft.world.level.block.entity.BlockEntity regionium$lockedBlockEntityReadWithType(
        BlockPos pos,
        net.minecraft.world.level.chunk.LevelChunk.EntityCreationType creationType,
        Operation<net.minecraft.world.level.block.entity.BlockEntity> original
    ) {
        synchronized (this) {
            return original.call(pos, creationType);
        }
    }

    @WrapMethod(method = "addAndRegisterBlockEntity")
    private void regionium$lockedBlockEntityAdd(
        net.minecraft.world.level.block.entity.BlockEntity blockEntity,
        Operation<Void> original
    ) {
        synchronized (this) {
            original.call(blockEntity);
        }
    }

    @WrapMethod(method = "setBlockEntity")
    private void regionium$lockedBlockEntitySet(
        net.minecraft.world.level.block.entity.BlockEntity blockEntity,
        Operation<Void> original
    ) {
        synchronized (this) {
            original.call(blockEntity);
        }
    }

    @WrapMethod(method = "removeBlockEntity")
    private void regionium$lockedBlockEntityRemove(
        BlockPos pos,
        Operation<Void> original
    ) {
        synchronized (this) {
            original.call(pos);
        }
    }
}

package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TheEndGatewayBlockEntity.class)
public abstract class TheEndGatewayBlockEntityRegioniumMixin {
    @Redirect(
        method = "findTallestBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private static BlockState regionium$readLoadedTerrain(BlockGetter level, BlockPos pos) {
        var chunk = RegioniumPortalChunkAccess.getLoadedChunk(
            (net.minecraft.world.level.Level) level,
            pos.getX() >> 4,
            pos.getZ() >> 4,
            ChunkStatus.FULL
        );
        if (chunk == null) {
            throw new IllegalStateException("End gateway search accessed an unprepared chunk at " + pos);
        }
        return chunk.getBlockState(pos);
    }
}

package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.EndGatewayFeature;

@Mixin(TheEndGatewayBlockEntity.class)
public interface TheEndGatewayBlockEntityRegioniumAccessorMixin {
    @Accessor("exitPortal")
    @Nullable BlockPos regionium$getExitPortal();

    @Accessor("exactTeleport")
    boolean regionium$isExactTeleport();

    @Invoker("findExitPosition")
    static BlockPos regionium$findExitPosition(Level level, BlockPos exitPortal) {
        throw new AssertionError();
    }

    @Invoker("findValidSpawnInChunk")
    static @Nullable BlockPos regionium$findValidSpawnInChunk(LevelChunk chunk) {
        throw new AssertionError();
    }

    @Invoker("spawnGatewayPortal")
    static void regionium$spawnGatewayPortal(ServerLevel level, BlockPos portalPos, EndGatewayFeature feature) {
        throw new AssertionError();
    }

    @Invoker("findTallestBlock")
    static BlockPos regionium$findTallestBlock(BlockGetter level, BlockPos around, int distance, boolean allowBedrock) {
        throw new AssertionError();
    }
}

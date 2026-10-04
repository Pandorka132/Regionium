package dev.pandor.regionium.mixins;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunk.class)
public interface LevelChunkRegioniumTickAccessorMixin {
    @Accessor("blockTicks")
    LevelChunkTicks<Block> regionium$getBlockTicks();

    @Accessor("fluidTicks")
    LevelChunkTicks<Fluid> regionium$getFluidTicks();
}

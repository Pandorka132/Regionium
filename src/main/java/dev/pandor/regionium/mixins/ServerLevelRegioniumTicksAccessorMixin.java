package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelRegioniumTicksAccessorMixin {
    @Accessor("blockTicks")
    LevelTicks<Block> regionium$getVanillaBlockTicks();

    @Accessor("fluidTicks")
    LevelTicks<Fluid> regionium$getVanillaFluidTicks();
}

package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelRegioniumAccessorMixin {
    @Accessor("entityTickList") EntityTickList regionium$getEntityTickList();
    @Accessor("blockTicks") LevelTicks<Block> regionium$getBlockTicks();
    @Accessor("fluidTicks") LevelTicks<Fluid> regionium$getFluidTicks();
}

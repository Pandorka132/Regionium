package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EnderDragonFight.class)
public interface EnderDragonFightRegioniumAccessorMixin {
    @Accessor("origin")
    BlockPos regionium$getOrigin();
}

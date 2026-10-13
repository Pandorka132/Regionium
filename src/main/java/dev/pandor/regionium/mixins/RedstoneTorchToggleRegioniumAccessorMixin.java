package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RedstoneTorchBlock.Toggle.class)
public interface RedstoneTorchToggleRegioniumAccessorMixin {
    @Accessor("pos")
    BlockPos regionium$getPos();

    @Accessor("when")
    long regionium$getWhen();
}

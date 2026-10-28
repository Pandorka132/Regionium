package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumTickOffsetAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntity.class)
abstract class BlockEntityRegioniumTickOffsetMixin implements RegioniumTickOffsetAccess {
}

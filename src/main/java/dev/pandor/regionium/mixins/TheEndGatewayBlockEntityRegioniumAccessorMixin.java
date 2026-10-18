package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TheEndGatewayBlockEntity.class)
public interface TheEndGatewayBlockEntityRegioniumAccessorMixin {
    @Accessor("exitPortal")
    @Nullable BlockPos regionium$getExitPortal();

    @Accessor("exactTeleport")
    boolean regionium$isExactTeleport();
}

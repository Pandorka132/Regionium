package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Optional;

@Mixin(Entity.class)
public interface EntityRegioniumPortalStateAccessorMixin {
    @Accessor("mainSupportingBlockPos")
    void regionium$setMainSupportingBlockPos(Optional<BlockPos> position);

    @Accessor("movementThisTick")
    ArrayDeque<?> regionium$getMovementThisTick();

    @Accessor("levelCallback")
    EntityInLevelCallback regionium$getLevelCallback();
}

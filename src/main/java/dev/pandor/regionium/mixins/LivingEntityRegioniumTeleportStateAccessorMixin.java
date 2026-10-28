package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/** Access to LivingEntity's cached climbable position, reset by Folia on teleport. */
@Mixin(LivingEntity.class)
public interface LivingEntityRegioniumTeleportStateAccessorMixin {
    @Accessor("lastClimbablePos")
    void regionium$setLastClimbablePos(Optional<BlockPos> position);
}

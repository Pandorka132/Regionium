package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * A teleport invalidates Entity's cached supporting-block position. Without
 * clearing it, a player can carry a BlockPos from the source region into the
 * destination region and the next movement tick may read that old chunk.
 */
@Mixin(Entity.class)
public abstract class EntityRegioniumTeleportMixin {
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("TAIL"))
    private void regionium$invalidateSupportingBlockAfterTeleportTo(
        ServerLevel level,
        double x,
        double y,
        double z,
        java.util.Set<net.minecraft.world.entity.Relative> relatives,
        float yRot,
        float xRot,
        boolean dismountVehicle,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            Entity entity = (Entity) (Object) this;
            EntityRegioniumPortalStateAccessorMixin access =
                (EntityRegioniumPortalStateAccessorMixin) (Object) entity;
            access.regionium$setMainSupportingBlockPos(Optional.empty());
            // Folia resets both cached movement-support state and the
            // per-tick movement history when teleporting. Clearing only
            // mainSupportingBlockPos leaves the old region's positions in
            // movementThisTick, which can make the next movement packet
            // inspect a chunk owned by the previous region.
            access.regionium$getMovementThisTick().clear();
            if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
                ((LivingEntityRegioniumTeleportStateAccessorMixin) (Object) livingEntity)
                    .regionium$setLastClimbablePos(Optional.empty());
            }
        }
    }

    @Inject(
        method = "teleportSetPosition(Lnet/minecraft/world/entity/PositionMoveRotation;Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V",
        at = @At("TAIL")
    )
    private void regionium$invalidateSupportingBlockAfterTeleportSetPosition(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        EntityRegioniumPortalStateAccessorMixin access =
            (EntityRegioniumPortalStateAccessorMixin) (Object) entity;
        access.regionium$setMainSupportingBlockPos(Optional.empty());
        access.regionium$getMovementThisTick().clear();
        if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            ((LivingEntityRegioniumTeleportStateAccessorMixin) (Object) livingEntity)
                .regionium$setLastClimbablePos(Optional.empty());
        }
    }
}

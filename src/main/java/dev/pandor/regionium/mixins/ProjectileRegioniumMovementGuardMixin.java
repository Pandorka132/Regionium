package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrowableProjectile.class)
abstract class ThrowableProjectileRegioniumMovementMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void regionium$guardCrossRegionMovement(CallbackInfo ci) {
        if (!RegioniumContext.canMoveWithinCurrentRegion((ThrowableProjectile) (Object) this)) {
            ci.cancel();
        }
    }
}

@Mixin(AbstractArrow.class)
abstract class AbstractArrowRegioniumMovementMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void regionium$guardCrossRegionMovement(CallbackInfo ci) {
        if (!RegioniumContext.canMoveWithinCurrentRegion((AbstractArrow) (Object) this)) {
            ci.cancel();
        }
    }
}

@Mixin(AbstractHurtingProjectile.class)
abstract class AbstractHurtingProjectileRegioniumMovementMixin {
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void regionium$guardCrossRegionMovement(CallbackInfo ci) {
        if (!RegioniumContext.canMoveWithinCurrentRegion((AbstractHurtingProjectile) (Object) this)) {
            ci.cancel();
        }
    }
}

@Mixin(LlamaSpit.class)
abstract class LlamaSpitRegioniumMovementMixin {
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void regionium$guardCrossRegionMovement(CallbackInfo ci) {
        if (!RegioniumContext.canMoveWithinCurrentRegion((LlamaSpit) (Object) this)) {
            ci.cancel();
        }
    }
}

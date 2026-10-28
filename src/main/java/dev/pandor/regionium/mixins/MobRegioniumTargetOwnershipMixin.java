package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Folia's guards against AI retaining a target owned by another region. */
@Mixin(Mob.class)
public abstract class MobRegioniumTargetOwnershipMixin {
    @Shadow
    private LivingEntity target;

    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true)
    private void regionium$clearForeignRegionTarget(CallbackInfoReturnable<LivingEntity> cir) {
        if (target != null
            && (!RegioniumContext.isCurrentThreadFor(target) || target.isRemoved())) {
            target = null;
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "asValidTarget", at = @At("HEAD"), cancellable = true)
    private void regionium$rejectForeignRegionTarget(
        LivingEntity target,
        CallbackInfoReturnable<LivingEntity> cir
    ) {
        if (target != null
            && (!RegioniumContext.isCurrentThreadFor(target) || target.isRemoved())) {
            cir.setReturnValue(null);
        }
    }
}

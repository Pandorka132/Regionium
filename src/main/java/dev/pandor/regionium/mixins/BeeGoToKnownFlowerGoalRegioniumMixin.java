package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ports Folia's known-flower position invalidation before the bee goal reads its target. */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$BeeGoToKnownFlowerGoal")
public abstract class BeeGoToKnownFlowerGoalRegioniumMixin {
    @Shadow @Final Bee this$0;

    @Inject(method = "canBeeUse", at = @At("HEAD"))
    private void regionium$forgetDistantFlower(CallbackInfoReturnable<Boolean> cir) {
        BlockPos flower = this.this$0.getSavedFlowerPos();
        if (flower != null && !flower.closerThan(this.this$0.blockPosition(), 48)) {
            this.this$0.setSavedFlowerPos(null);
        }
    }
}

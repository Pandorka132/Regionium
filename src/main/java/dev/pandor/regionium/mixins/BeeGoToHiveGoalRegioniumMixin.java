package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ports Folia's hive-position invalidation before the bee goal reads its target. */
@Mixin(targets = "net.minecraft.world.entity.animal.bee.Bee$BeeGoToHiveGoal")
public abstract class BeeGoToHiveGoalRegioniumMixin {
    @Shadow @Final Bee this$0;

    @Inject(method = "canBeeUse", at = @At("HEAD"))
    private void regionium$forgetDistantHive(CallbackInfoReturnable<Boolean> cir) {
        BlockPos hive = this.this$0.getHivePos();
        if (hive != null && !hive.closerThan(this.this$0.blockPosition(), 48)) {
            this.this$0.setHivePos(null);
        }
    }
}

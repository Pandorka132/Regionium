package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Matches Folia's same-level check before measuring a pet owner's distance. */
@Mixin(FollowOwnerGoal.class)
public abstract class FollowOwnerGoalRegioniumMixin {
    @WrapOperation(
        method = "canContinueToUse",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/TamableAnimal;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"
        )
    )
    private double regionium$avoidCrossLevelDistanceRead(
        TamableAnimal entity,
        Entity owner,
        Operation<Double> original
    ) {
        return entity.level() == owner.level()
            ? original.call(entity, owner)
            : Double.MAX_VALUE;
    }
}

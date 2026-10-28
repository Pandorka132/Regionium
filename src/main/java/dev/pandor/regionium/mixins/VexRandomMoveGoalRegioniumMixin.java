package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Vex;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Ports Folia's ownership check before Vex reads its bound origin. */
@Mixin(targets = "net.minecraft.world.entity.monster.Vex$VexRandomMoveGoal")
public abstract class VexRandomMoveGoalRegioniumMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Vex;getBoundOrigin()Lnet/minecraft/core/BlockPos;"))
    private @Nullable BlockPos regionium$rejectForeignBoundOrigin(Vex vex) {
        BlockPos origin = vex.getBoundOrigin();
        var region = RegioniumContext.currentRegion();
        if (origin != null && region != null
            && !Regionium.scheduler().isCurrentRegionFor(region.level(), origin)) {
            return null;
        }
        return origin;
    }
}

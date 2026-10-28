package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors Folia's ownership assertion at CollectingNeighborUpdater.addAndRun. */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterRegioniumOwnershipMixin {
    @Shadow @Final private Level level;

    @Inject(method = "addAndRun", at = @At("HEAD"))
    private void regionium$ensureOwningRegion(BlockPos pos, @Coerce Object update, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel
            && RegioniumContext.currentRegion() != null
            && !Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)) {
            throw new IllegalStateException("Adding block without owning region");
        }
    }
}

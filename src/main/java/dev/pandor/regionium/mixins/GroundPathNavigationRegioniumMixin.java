package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.PathNavigationRegioniumAccess;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Folia's ownership check before ground navigation inspects a target chunk. */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationRegioniumMixin {
    @Inject(
        method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$rejectForeignTargetChunk(
        BlockPos pos,
        int reachRange,
        CallbackInfoReturnable<Path> cir
    ) {
        if (!RegioniumContext.isRegionThread() || !(((PathNavigationRegioniumAccess) (PathNavigation) (Object) this).regionium$getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || !Regionium.scheduler().regionizer().ownsChunkSquare(
            current, serverLevel, pos.getX() >> 4, pos.getZ() >> 4, 0
        )) {
            cir.setReturnValue(null);
        }
    }
}

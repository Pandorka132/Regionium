package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.PathNavigationRegioniumAccess;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Keeps pathfinding time and destination ownership region-local. */
@Mixin(PathNavigation.class)
public abstract class PathNavigationRegioniumMixin implements PathNavigationRegioniumAccess {
    @Shadow @Final protected Level level;

    @Override
    @Accessor("level")
    public abstract Level regionium$getLevel();
    @Shadow protected int tick;

    @WrapOperation(
        method = "recomputePath",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getGameTime()J"
        )
    )
    private long regionium$useRegionNavigationClock(Level ignored, Operation<Long> original) {
        return RegioniumContext.isRegionThread() ? this.tick : original.call(ignored);
    }

    @Inject(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$rejectForeignDestinationChunks(
        Set<BlockPos> targets,
        int radiusOffset,
        boolean above,
        int reachRange,
        float maxPathLength,
        CallbackInfoReturnable<Path> cir
    ) {
        if (!RegioniumContext.isRegionThread() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null) {
            cir.setReturnValue(null);
            return;
        }

        for (BlockPos pos : targets) {
            if (!Regionium.scheduler().regionizer().ownsChunkSquare(
                current, serverLevel, pos.getX() >> 4, pos.getZ() >> 4, 0
            )) {
                cir.setReturnValue(null);
                return;
            }
        }
    }
}

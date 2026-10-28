package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.Regionium;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.FallLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

@Mixin(FallLocation.class)
public abstract class FallLocationRegioniumMixin {
    @Redirect(method = "getCurrentFallLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLastClimbablePos()Ljava/util/Optional;"))
    private static Optional<BlockPos> regionium$ignoreForeignClimbablePosition(LivingEntity entity) {
        Optional<BlockPos> last = entity.getLastClimbablePos();
        if (last.isEmpty() || !(entity.level() instanceof ServerLevel level)) return last;
        var region = RegioniumContext.currentRegion();
        if (region == null || region.level() != level) return last;
        return Regionium.scheduler().isCurrentRegionFor(level, last.get()) ? last : Optional.empty();
    }
}

package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityLifecycleRegioniumMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void regionium$registerFreshEntity(
        Entity entity, CallbackInfoReturnable<Boolean> cir
    ) {
        if (RegioniumContext.isRegionThread()) {
            Regionium.scheduler().trackEntity(entity);
        }
    }
}

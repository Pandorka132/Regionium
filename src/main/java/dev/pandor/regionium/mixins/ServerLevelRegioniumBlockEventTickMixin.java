package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelRegioniumBlockEventTickMixin {
    @Inject(method = "runBlockEvents", at = @At("HEAD"), cancellable = true)
    private void regionium$dispatchBlockEvents(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        var region = dev.pandor.regionium.core.RegioniumContext.currentRegion();
        if (region != null) {
            Regionium.scheduler().dispatchBlockEventsForRegion(level, region);
            Regionium.scheduler().finishRegionalPhase(level, "block-events");
            ci.cancel();
        }
    }
}

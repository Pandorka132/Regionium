package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class RegioniumServerTickMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void regionium$tickRegions(CallbackInfo ci) {
        Regionium.scheduler().tick((MinecraftServer) (Object) this);
    }
}

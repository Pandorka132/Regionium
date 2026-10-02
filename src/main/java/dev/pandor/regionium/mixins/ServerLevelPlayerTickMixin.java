package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerLevelPlayerTickMixin {
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void regionium$offloadPlayerTick(CallbackInfo ci) {
        if (!dev.pandor.regionium.core.RegioniumContext.isRegionThread()) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            Regionium.scheduler().execute(0, player::doTick);
            ci.cancel();
        }
    }
}

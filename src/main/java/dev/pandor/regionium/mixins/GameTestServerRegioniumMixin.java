package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameTestServer.class)
public abstract class GameTestServerRegioniumMixin {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void regionium$beginGameTestTick(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        Regionium.scheduler().setGameTestDriven(true);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void regionium$driveRegions(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        Regionium.scheduler().driveGameTestRegions();
    }
}

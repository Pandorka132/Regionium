package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Regionium's independent region workers before vanilla starts
 * shutting down chunk systems.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerRegioniumShutdownMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void regionium$haltRegionsBeforeShutdown(CallbackInfo ci) {
        Regionium.LOGGER.trace("[Regionium] Halting region scheduler before server shutdown");
        Regionium.scheduler().close();
        Regionium.LOGGER.trace("[Regionium] Region scheduler halted");
    }
}

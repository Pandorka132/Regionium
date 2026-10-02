package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla packet handlers guard themselves with PacketProcessor.isSameThread().
 * A Regionium worker is a legitimate execution owner for world/player packet
 * work, so make that guard recognize the active Regionium execution context.
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorRegioniumMixin {
    @Inject(method = "isSameThread", at = @At("HEAD"), cancellable = true)
    private void regionium$isSameThread(CallbackInfoReturnable<Boolean> cir) {
        if (RegioniumContext.isRegionThread()) {
            cir.setReturnValue(true);
        }
    }
}

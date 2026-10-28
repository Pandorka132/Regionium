package dev.pandor.regionium.mixins;

import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors Folia's guard against accessing world time while no behaviors need stopping. */
@Mixin(Brain.class)
public abstract class BrainRegioniumStopAllMixin {
    @Inject(method = "stopAll", at = @At("HEAD"), cancellable = true)
    private void regionium$skipEmptyStopAll(CallbackInfo ci) {
        if (((Brain<?>) (Object) this).getRunningBehaviors().isEmpty()) {
            ci.cancel();
        }
    }
}

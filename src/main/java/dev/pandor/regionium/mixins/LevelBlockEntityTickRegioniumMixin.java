package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves server-side block-entity execution into the owning Regionium worker. */
@Mixin(Level.class)
public abstract class LevelBlockEntityTickRegioniumMixin {
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void regionium$parallelBlockEntities(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) {
            if (!dev.pandor.regionium.core.RegioniumContext.isRegionThread()) {
                // Region clocks own block-entity ticking. Cancel the vanilla
                // global pass so the same ticker is never run twice.
                ci.cancel();
                return;
            }
            Regionium.scheduler().parallelTickBlockEntities(level);
            Regionium.scheduler().finishRegionalPhase(level, "block-entities");
            ci.cancel();
        }
    }
}

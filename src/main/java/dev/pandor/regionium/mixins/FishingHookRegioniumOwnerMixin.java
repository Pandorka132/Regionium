package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Folia's owner-region guard before a fishing hook reads its owner. */
@Mixin(FishingHook.class)
public abstract class FishingHookRegioniumOwnerMixin {
    @Inject(method = "shouldStopFishing", at = @At("HEAD"), cancellable = true)
    private void regionium$stopWhenOwnerIsForeign(
        Player owner,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!RegioniumContext.isCurrentThreadFor(owner)) {
            cir.setReturnValue(true);
        }
    }
}

package dev.pandor.regionium.mixins;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a tameable from using an owner reference across dimension/removal transitions. */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalRegioniumTeleportMixin {
    @Inject(method = "tryToTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void regionium$rejectInvalidOwnerTeleport(CallbackInfo ci) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        LivingEntity owner = self.getOwner();
        if (owner != null && (owner.isRemoved() || owner.level() != self.level())) {
            ci.cancel();
        }
    }
}

package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Folia's owner-thread-safe Projectile.getOwner() behavior. */
@Mixin(Projectile.class)
public abstract class ProjectileRegioniumOwnerMixin {
    @Inject(method = "getOwner", at = @At("HEAD"))
    private void regionium$requireProjectileOwnerThread(CallbackInfoReturnable<Entity> cir) {
        Entity projectile = (Entity) (Object) this;
        if (((EntityRegioniumPortalStateAccessorMixin) (Object) projectile).regionium$getLevelCallback()
                != net.minecraft.world.level.entity.EntityInLevelCallback.NULL
            && !RegioniumContext.isCurrentThreadFor(projectile)) {
            throw new IllegalStateException("Cannot access projectile owner outside its owning tick context");
        }
    }

    @Inject(method = "getOwner", at = @At("RETURN"), cancellable = true)
    private void regionium$hideForeignRegionOwner(CallbackInfoReturnable<Entity> cir) {
        Entity owner = cir.getReturnValue();
        if (owner != null
            && (!RegioniumContext.isCurrentThreadFor(owner) || owner.isRemoved())) {
            cir.setReturnValue(null);
        }
    }
}

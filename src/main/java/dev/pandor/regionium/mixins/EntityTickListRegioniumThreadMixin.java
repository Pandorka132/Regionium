package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EntityTickList is a pair of plain FastUtil maps, not a concurrent
 * collection. Region workers therefore must never mutate it directly while
 * the server thread is preparing the next entity snapshot.
 *
 * Entity lifecycle callbacks are committed by the vanilla server thread.
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListRegioniumThreadMixin {
    @Inject(method = "add", at = @At("TAIL"))
    private void regionium$trackVanillaAdd(Entity entity, CallbackInfo ci) {
        dev.pandor.regionium.Regionium.scheduler().trackEntity(entity);
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void regionium$untrackVanillaRemove(Entity entity, CallbackInfo ci) {
        dev.pandor.regionium.Regionium.scheduler().untrackEntity(entity);
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void regionium$deferAdd(Entity entity, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }
        dev.pandor.regionium.Regionium.scheduler().trackEntity(entity);
        if (entity.level().getServer() != null) {
            entity.level().getServer().execute(
                () -> ((EntityTickList) (Object) this).add(entity)
            );
        }
        ci.cancel();
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void regionium$deferRemove(Entity entity, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }
        dev.pandor.regionium.Regionium.scheduler().untrackEntity(entity);
        if (entity.level().getServer() != null) {
            entity.level().getServer().execute(
                () -> ((EntityTickList) (Object) this).remove(entity)
            );
        }
        ci.cancel();
    }
}

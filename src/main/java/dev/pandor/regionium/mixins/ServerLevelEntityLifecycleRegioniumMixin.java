package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityLifecycleRegioniumMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void regionium$registerFreshEntity(
        Entity entity, CallbackInfoReturnable<Boolean> cir
    ) {
        /*
         * ServerPlayer has a distinct lifecycle boundary: addFreshEntity()
         * happens before PlayerList.placeNewPlayer() has installed the PLAY
         * connection. Folia only puts the player into the active region's
         * local player/connection state once the gameplay session exists.
         * Register ordinary entities here, but let PlayerList's PLAY lifecycle
         * hook register ServerPlayer.
         */
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) {
            Regionium.scheduler().trackEntity(entity);
        }
    }
}

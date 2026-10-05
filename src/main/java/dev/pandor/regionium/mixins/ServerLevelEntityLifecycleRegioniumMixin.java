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
        // Entity creation is a server-thread lifecycle boundary. Register the
        // entity with its current chunk owner here as well as from region-owned
        // lifecycle paths; GameTestServer creates test entities on the server thread.
        Regionium.scheduler().trackEntity(entity);
    }
}

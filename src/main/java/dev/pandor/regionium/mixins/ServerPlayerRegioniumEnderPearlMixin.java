package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Matches Folia: do not keep a player-owned pearl set that is mutated across region threads. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRegioniumEnderPearlMixin {
    @Inject(method = "registerEnderPearl(Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$skipEnderPearlRegistration(ThrownEnderpearl pearl, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "deregisterEnderPearl(Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$skipEnderPearlDeregistration(ThrownEnderpearl pearl, CallbackInfo ci) {
        ci.cancel();
    }
}

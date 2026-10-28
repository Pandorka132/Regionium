package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.EntityRegioniumPortalAccess;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes player ender-pearl teleports through Regionium's region-owned async transfer path. */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlRegioniumMixin {
    @Inject(
        method = "onHit(Lnet/minecraft/world/phys/HitResult;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile;onHit(Lnet/minecraft/world/phys/HitResult;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void regionium$teleportPlayerAsync(HitResult hitResult, CallbackInfo ci) {
        ThrownEnderpearl pearl = (ThrownEnderpearl) (Object) this;
        if (!(pearl.level() instanceof ServerLevel level) || !RegioniumContext.isRegionThread()) {
            return;
        }

        Entity owner = pearl.getOwner();
        if (!(owner instanceof ServerPlayer player) || player.level() != level) {
            return;
        }

        boolean spawnEndermite = pearl.getRandom().nextFloat() < 0.05F
            && level.isSpawningMonsters()
            && level.getLevelData().getDifficulty() != Difficulty.PEACEFUL;

        ((EntityRegioniumPortalAccess) (Object) player).regionium$startEnderPearlTransfer(
            player, level, pearl.oldPosition(), spawnEndermite
        );

        // The transfer owns its source/destination tickets and restores the
        // entity if destination placement fails; vanilla's synchronous path
        // must not run after it has been scheduled.
        pearl.discard();
        ci.cancel();
    }
}

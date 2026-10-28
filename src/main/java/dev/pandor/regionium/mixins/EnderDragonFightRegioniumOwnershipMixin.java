package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightRegioniumOwnershipMixin {
    @Shadow private ServerLevel level;
    @Shadow private BlockPos origin;

    @Redirect(
        method = "scanState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getDragons()Ljava/util/List;")
    )
    private List<? extends EnderDragon> regionium$filterDragonsToCurrentOwner(ServerLevel level) {
        List<EnderDragon> dragons = new ArrayList<>(level.getDragons());
        dragons.removeIf(dragon -> !RegioniumContext.isCurrentThreadFor(dragon));
        return dragons;
    }

    @Inject(method = "onCrystalDestroyed", at = @At("HEAD"), cancellable = true)
    private void regionium$guardCrystalDestroyed(EndCrystal crystal, DamageSource source, CallbackInfo ci) {
        if (RegioniumContext.currentRegion() != null
            && !RegioniumContext.ownsBlockNeighborhood(this.level, this.origin, 0)) {
            ci.cancel();
        }
    }

    @Inject(method = "tryRespawn", at = @At("HEAD"), cancellable = true)
    private void regionium$guardRespawn(CallbackInfo ci) {
        if (RegioniumContext.currentRegion() != null
            && !RegioniumContext.ownsBlockNeighborhood(this.level, this.origin, 0)) {
            ci.cancel();
        }
    }
}

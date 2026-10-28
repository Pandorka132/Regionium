package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.RaidsRegioniumAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelRaidsRegioniumMixin {
    @Inject(method = "getRaidAt", at = @At("HEAD"), cancellable = true)
    private void regionium$filterNearbyRaidsByOwner(BlockPos pos, CallbackInfoReturnable<Raid> cir) {
        ServerLevel level = (ServerLevel) (Object) this;
        cir.setReturnValue(((RaidsRegioniumAccess) level.getRaids()).regionium$getNearbyRaid(level, pos, 9216));
    }
}

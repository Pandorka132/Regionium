package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.commands.SetSpawnCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SetSpawnCommand.class)
public abstract class SetSpawnCommandRegioniumMixin {
    @Redirect(
        method = "setSpawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V"
        )
    )
    private static void regionium$setSpawnOnPlayerOwner(
        ServerPlayer target,
        ServerPlayer.RespawnConfig respawnConfig,
        boolean forced
    ) {
        var current = RegioniumContext.currentRegion();
        var owner = Regionium.scheduler().ownerOf(target);
        if (owner == null || owner == current) {
            target.setRespawnPosition(respawnConfig, forced);
        } else {
            Regionium.scheduler().executeEntity(target, () -> target.setRespawnPosition(respawnConfig, forced));
        }
    }
}

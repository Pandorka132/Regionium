package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces player execution ownership at the actual ServerPlayer tick
 * boundary. The connection tick can change a player's position before
 * ServerPlayer.doTick() is entered, so checking only the connection list is
 * insufficient.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRegioniumTickOwnershipMixin {
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void regionium$requireSpatialOwner(CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        ServerPlayer player = (ServerPlayer) (Object) this;
        var current = RegioniumContext.currentRegion();

        if (current == null || player.level() == null) {
            return;
        }

        if (Regionium.scheduler().regionizer()
            .owner((net.minecraft.server.level.ServerLevel) player.level(), player.chunkPosition().pack()) != current) {
            Regionium.LOGGER.warn(
                "[PLAYER-TRANSFER-GATE] player={} region={} chunk={} owner={}",
                player.getGameProfile().name(),
                current.id(),
                player.chunkPosition(),
                Regionium.scheduler().regionizer()
                    .owner((net.minecraft.server.level.ServerLevel) player.level(), player.chunkPosition().pack())
            );
            ci.cancel();
        }
    }
}

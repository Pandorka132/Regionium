package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves world-mutating player packet handling onto the player's Regionium
 * owner. Vanilla receives these packets on the server/network thread, while
 * the corresponding ServerLevel tick is owned by a Regionium worker.
 *
 * Keeping the entire handler on that worker prevents redstone, piston and
 * neighbor-update queues from being mutated concurrently with ServerLevel.tick().
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerRegioniumMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntityAndWait(
                null,
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handlePlayerAction(packet)
            );
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void regionium$handleUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntityAndWait(
                null,
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleUseItemOn(packet)
            );
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void regionium$handleUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntityAndWait(
                null,
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleUseItem(packet)
            );
        }
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void regionium$handleInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntityAndWait(
                null,
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleInteract(packet)
            );
        }
    }

    private boolean regionium$onRegionThread() {
        return dev.pandor.regionium.core.RegioniumContext.isRegionThread();
    }
}

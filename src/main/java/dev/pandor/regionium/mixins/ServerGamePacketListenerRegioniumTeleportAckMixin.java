package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import dev.pandor.regionium.core.RegioniumConnectionCleanup;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rejects teleport acknowledgements whose accepted destination crosses ownership. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerRegioniumTeleportAckMixin {
    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void regionium$deferGlobalConnectionRemoval(
        net.minecraft.network.DisconnectionDetails details,
        CallbackInfo ci
    ) {
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        if (listener.player.connection == listener
            && listener.player.isRemoved()
            && !RegioniumPortalDispatcher.isTransferPending(listener.player)
            && !((ServerGamePacketListenerRegioniumAccessorMixin) listener)
                .regionium$isWaitingForSwitchToConfig()
            && Regionium.scheduler().ownerOf(listener.player) != null) {
            RegioniumConnectionCleanup.enqueue(
                ((dev.pandor.regionium.access.ServerCommonPacketListenerRegioniumAccess) listener)
                    .regionium$getConnection()
            );
        }
    }

    @Inject(
        method = "handleAcceptTeleportPacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;absSnapTo(DDDFF)V"
        ),
        cancellable = true
    )
    private void regionium$rejectOutOfRegionTeleportAck(
        net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet,
        CallbackInfo ci
    ) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || !RegioniumContext.isRegionThread()) {
            return;
        }

        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        ServerLevel level = listener.player.level();
        Vec3 destination = ((ServerGamePacketListenerRegioniumAccessorMixin) this)
            .regionium$getAwaitingPositionFromClient();
        if (destination == null) {
            return;
        }

        int centerX = Mth.floor(destination.x) >> 4;
        int centerZ = Mth.floor(destination.z) >> 4;
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                if (Regionium.scheduler().regionizer().owner(level, ChunkPos.pack(x, z)) != current) {
                    Regionium.LOGGER.warn(
                        "Disconnecting '{}' for out of region teleport accept",
                        listener.player.getGameProfile().name()
                    );
                    listener.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"));
                    ci.cancel();
                    return;
                }
            }
        }
    }
}

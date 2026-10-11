package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumChunkSendBatch;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerRegioniumMixin {
    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$preservePacketOrderDuringChunkSend(
        Packet<?> packet,
        @Nullable ChannelFutureListener callback,
        CallbackInfo ci
    ) {
        if (RegioniumChunkSendBatch.shouldBuffer(
            (ServerCommonPacketListenerImpl) (Object) this,
            packet,
            callback
        )) {
            ci.cancel();
        }
    }
}

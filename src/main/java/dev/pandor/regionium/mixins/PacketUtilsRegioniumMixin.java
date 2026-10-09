package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.network.protocol.PacketUtils.class)
public abstract class PacketUtilsRegioniumMixin {
    @Inject(
        method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static <T extends PacketListener> void regionium$ensure(
        Packet<T> packet, T listener, ServerLevel level, CallbackInfo ci
    ) {
        // A packet being executed by its owning region is already on the
        // correct execution context. Vanilla must not continue into its
        // thread check, otherwise it throws RunningOnDifferentThreadException.
        if (listener instanceof ServerGamePacketListenerImpl game
            && RegioniumContext.isRegionThread()
            && Regionium.scheduler().isOwnedByCurrentRegion(game.player)) {
            ci.cancel();
            return;
        }
        regionium$route(packet, listener, ci);
    }

    @Inject(
        method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static <T extends PacketListener> void regionium$ensureProcessor(
        Packet<T> packet, T listener, PacketProcessor processor, CallbackInfo ci
    ) {
        // This is the critical Folia invariant: once a packet is executing
        // through its owning PacketProcessor, PacketUtils must not enqueue it
        // again. PacketProcessor.isSameThread() is region-aware in Regionium.
        if (processor.isSameThread()) {
            ci.cancel();
            return;
        }

        // PacketProcessor is not bound to a physical worker in Folia-style
        // region ticking. Ownership, not Thread affinity, decides whether
        // this packet is already executing on the correct context.
        if (listener instanceof ServerGamePacketListenerImpl game
            && RegioniumContext.isRegionThread()
            && Regionium.scheduler().isOwnedByCurrentRegion(game.player)) {
            ci.cancel();
            return;
        }

        regionium$route(packet, listener, ci);
    }

    private static <T extends PacketListener> void regionium$route(
        Packet<T> packet, T listener, CallbackInfo ci
    ) {
        if (RegioniumContext.isRegionThread()) {
            if (listener instanceof ServerGamePacketListenerImpl game
                && Regionium.scheduler().isOwnedByCurrentRegion(game.player)) {
                return;
            }
            if (!(listener instanceof ServerGamePacketListenerImpl)) {
                return;
            }
        }

        if (listener instanceof ServerGamePacketListenerImpl game) {
            Regionium.LOGGER.trace(
                "[PACKET-IN] player={} packet={} owner={} current={} thread={}",
                game.player.getGameProfile().name(),
                packet.getClass().getSimpleName(),
                Regionium.scheduler().ownerOf(game.player),
                RegioniumContext.currentRegion(),
                Thread.currentThread().getName()
            );
            ((ServerPlayerRegioniumPacketAccess) (Object) game.player)
                .regionium$schedulePacket(listener, packet);
        } else if (listener instanceof ServerConfigurationPacketListenerImpl
            || listener instanceof ServerLoginPacketListenerImpl) {
            Regionium.scheduler().scheduleGlobalPacket(listener, packet);
        } else {
            return;
        }

        ci.cancel();
        throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
    }
}

package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.PacketProcessorListenerAndPacketAccess;
import dev.pandor.regionium.access.PacketProcessorRegioniumAccess;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Queue;

@Mixin(PacketProcessor.class)
public abstract class PacketProcessorRegioniumMixin implements dev.pandor.regionium.access.PacketProcessorRegioniumAccess {
    @Shadow private Queue<?> packetsToBeHandled;
    @Shadow private boolean closed;

    /**
     * PacketProcessor in vanilla binds to a fixed Thread. Folia's region
     * threads are not fixed: a region may execute on any worker. While a
     * queued packet is being handled, mark this processor as the active
     * processor so PacketUtils sees the packet as already running on its
     * owning thread instead of enqueueing it again.
     */
    private static final ThreadLocal<PacketProcessor> REGIONIUM_CURRENT = new ThreadLocal<>();

    @org.spongepowered.asm.mixin.injection.Inject(
        method = "isSameThread",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
        cancellable = true
    )
    private void regionium$isSameThread(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (REGIONIUM_CURRENT.get() == (PacketProcessor) (Object) this) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public boolean regionium$hasPackets() {
        return !packetsToBeHandled.isEmpty();
    }

    @Override
    public boolean regionium$executeSinglePacket() {
        if (closed) return false;
        Object queued = packetsToBeHandled.poll();
        if (queued == null) return false;
        PacketProcessor self = (PacketProcessor) (Object) this;
        PacketProcessor previous = REGIONIUM_CURRENT.get();
        REGIONIUM_CURRENT.set(self);
        try {
            net.minecraft.network.protocol.Packet<?> packet = null;
            try {
                java.lang.reflect.Field packetField = queued.getClass().getDeclaredField("packet");
                packetField.setAccessible(true);
                packet = (net.minecraft.network.protocol.Packet<?>) packetField.get(queued);
            } catch (Throwable ignored) {
                // Diagnostic only; packet execution itself must not depend on reflection.
            }
            net.minecraft.network.PacketListener packetListener = null;
            try {
                java.lang.reflect.Field listenerField = queued.getClass().getDeclaredField("listener");
                listenerField.setAccessible(true);
                packetListener = (net.minecraft.network.PacketListener) listenerField.get(queued);
            } catch (Throwable ignored) {
                // Diagnostic only.
            }
            net.minecraft.server.network.ServerGamePacketListenerImpl game =
                packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl g ? g : null;
            dev.pandor.regionium.Regionium.LOGGER.info(
                "[PACKET-EXEC] player={} packet={} region={} owner={} thread={}",
                game != null ? game.player.getGameProfile().name() : "<non-player>",
                packet != null ? packet.getClass().getSimpleName() : queued.getClass().getSimpleName(),
                dev.pandor.regionium.core.RegioniumContext.currentRegion(),
                game != null ? dev.pandor.regionium.Regionium.scheduler().ownerOf(game.player) : null,
                Thread.currentThread().getName()
            );
            ((dev.pandor.regionium.access.PacketProcessorListenerAndPacketAccess) queued).regionium$handle();
        } finally {
            if (previous == null) {
                REGIONIUM_CURRENT.remove();
            } else {
                REGIONIUM_CURRENT.set(previous);
            }
        }
        return true;
    }
}

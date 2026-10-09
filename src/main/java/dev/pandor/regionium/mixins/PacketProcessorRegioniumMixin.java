package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.PacketProcessorListenerAndPacketAccess;
import dev.pandor.regionium.access.PacketProcessorRegioniumAccess;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Queue;

/**
 * Fabric/vanilla 26.3 does not expose Folia's PacketProcessor.hasPackets()
 * and executeSinglePacket() API. Expose the same operations without changing
 * PacketProcessor's thread-affinity semantics or introducing a worker-thread
 * ThreadLocal.
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorRegioniumMixin implements PacketProcessorRegioniumAccess {
    @Shadow private Queue<?> packetsToBeHandled;
    @Shadow private boolean closed;

    @Override
    public boolean regionium$hasPackets() {
        return !packetsToBeHandled.isEmpty();
    }

    @Override
    public boolean regionium$executeSinglePacket() {
        if (closed) {
            return false;
        }

        Object queued = packetsToBeHandled.poll();
        if (queued == null) {
            return false;
        }

        ((PacketProcessorListenerAndPacketAccess) queued).regionium$handle();
        return true;
    }
}

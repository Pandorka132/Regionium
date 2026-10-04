package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.PacketProcessorListenerAndPacketAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public interface PacketProcessorListenerAndPacketMixin extends dev.pandor.regionium.access.PacketProcessorListenerAndPacketAccess {
    @Override
    @Invoker("handle")
    void regionium$handle();
}

package dev.pandor.regionium.access;

import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;

public interface ServerPlayerRegioniumPacketAccess {
    void regionium$stopAcceptingPackets();

    void regionium$updateRegion(dev.pandor.regionium.core.RegioniumWorldData region);

    <T extends PacketListener> void regionium$schedulePacket(T listener, Packet<T> packet);

    PacketProcessor regionium$getPacketProcessor();
}

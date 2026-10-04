package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.PacketProcessorRegioniumAccess;
import dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRegioniumPacketMixin implements ServerPlayerRegioniumPacketAccess {
    private final PacketProcessor regionium$packetProcessor = new PacketProcessor(null);
    private volatile RegioniumWorldData regionium$lastRegion;

    @Override
    public void regionium$stopAcceptingPackets() {
        regionium$packetProcessor.close();
    }

    @Override
    public void regionium$updateRegion(RegioniumWorldData region) {
        regionium$lastRegion = region;
        if (region != null && ((dev.pandor.regionium.access.PacketProcessorRegioniumAccess) regionium$packetProcessor).regionium$hasPackets()) {
            Regionium.scheduler().notifyRegionPackets(region);
        }
    }

    @Override
    public <T extends PacketListener> void regionium$schedulePacket(T listener, Packet<T> packet) {
        regionium$packetProcessor.scheduleIfPossible(listener, packet);
        boolean queued = ((PacketProcessorRegioniumAccess) regionium$packetProcessor).regionium$hasPackets();
        RegioniumWorldData region = regionium$lastRegion;
        Regionium.LOGGER.info(
            "[PACKET-QUEUE] player={} packet={} scheduled={} region={} thread={}",
            ((ServerPlayer) (Object) this).getGameProfile().name(),
            packet.getClass().getSimpleName(),
            queued,
            region,
            Thread.currentThread().getName()
        );
        if (region != null) {
            Regionium.scheduler().notifyRegionPackets(region);
        }
    }

    @Override
    public PacketProcessor regionium$getPacketProcessor() {
        return regionium$packetProcessor;
    }
}

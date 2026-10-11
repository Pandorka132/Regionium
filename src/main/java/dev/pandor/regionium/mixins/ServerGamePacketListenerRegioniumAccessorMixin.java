package dev.pandor.regionium.mixins;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerRegioniumAccessorMixin {
    @Accessor("awaitingTeleport")
    int regionium$getAwaitingTeleport();
}

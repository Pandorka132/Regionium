package dev.pandor.regionium.mixins;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerRegioniumAccessorMixin {
    @Accessor("awaitingTeleport")
    int regionium$getAwaitingTeleport();

    @Accessor("awaitingPositionFromClient")
    Vec3 regionium$getAwaitingPositionFromClient();

    @Accessor("waitingForSwitchToConfig")
    boolean regionium$isWaitingForSwitchToConfig();
}

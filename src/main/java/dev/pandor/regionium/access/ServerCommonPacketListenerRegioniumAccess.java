package dev.pandor.regionium.access;

import net.minecraft.network.Connection;

/** Access to the transport owned by a common packet listener. */
public interface ServerCommonPacketListenerRegioniumAccess {
    Connection regionium$getConnection();
}

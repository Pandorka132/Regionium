package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerRegioniumMixin {
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;tick()V"
        )
    )
    private void regionium$regionOwnedConnectionTick(Connection connection, Operation<Void> original) {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
            // ServerConnectionListener.tick() runs on the global server thread,
            // so there is no current Regionium context here. Once a player has
            // an owner, the global connection tick must leave it alone. The
            // owning region ticks it in tickConnectionsForRegion().
            if (Regionium.scheduler().ownerOf(listener.player) != null) {
                return;
            }
        }
        // Login/status/configuration connections still need vanilla's global
        // connection tick. This is what allows a client to finish joining.
        original.call(connection);
    }
}

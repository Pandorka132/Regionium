package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumConnectionCleanup;
import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerRegioniumMixin {
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;iterator()Ljava/util/Iterator;"
        )
    )
    private Iterator<Connection> regionium$skipRegionOwnedConnections(
        List<Connection> connections,
        Operation<Iterator<Connection>> original
    ) {
        Iterator<Connection> delegate = original.call(connections);
        return new Iterator<>() {
            private Connection next;
            private boolean prepared;
            private boolean canRemove;

            private void prepareNext() {
                if (prepared) {
                    return;
                }

                while (delegate.hasNext()) {
                    Connection candidate = delegate.next();
                    if (RegioniumConnectionCleanup.isPending(candidate)) {
                        if (regionium$canCompleteOwnerCleanup(candidate)) {
                            // This iterator runs under ServerConnectionListener's
                            // global list monitor. Remove the shared registry
                            // entry here, but do not repeat Connection.tick()'s
                            // already completed handleDisconnection() call.
                            delegate.remove();
                            RegioniumConnectionCleanup.complete(candidate);
                            continue;
                        }

                        if (!regionium$stillRegionOwned(candidate)) {
                            RegioniumConnectionCleanup.complete(candidate);
                        }
                    }

                    if (regionium$isRegionOwnedPlayConnection(candidate)) {
                        continue;
                    }

                    next = candidate;
                    prepared = true;
                    return;
                }
            }

            @Override
            public boolean hasNext() {
                prepareNext();
                return prepared;
            }

            @Override
            public Connection next() {
                prepareNext();
                if (!prepared) {
                    throw new NoSuchElementException();
                }

                Connection result = next;
                next = null;
                prepared = false;
                canRemove = true;
                return result;
            }

            @Override
            public void remove() {
                if (!canRemove) {
                    throw new IllegalStateException();
                }
                delegate.remove();
                canRemove = false;
            }
        };
    }

@org.spongepowered.asm.mixin.Unique
    private static boolean regionium$isRegionOwnedPlayConnection(Connection connection) {
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return false;
        }

        return regionium$stillRegionOwned(connection);
    }

@org.spongepowered.asm.mixin.Unique
    private static boolean regionium$stillRegionOwned(Connection connection) {
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return false;
        }

        return listener.player.connection == listener
            && !listener.player.isRemoved()
            && !((ServerGamePacketListenerRegioniumAccessorMixin) listener)
                .regionium$isWaitingForSwitchToConfig()
            && Regionium.scheduler().ownerOf(listener.player) != null;
    }

@org.spongepowered.asm.mixin.Unique
    private static boolean regionium$canCompleteOwnerCleanup(Connection connection) {
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            return false;
        }

        return listener.player.connection == listener
            && listener.player.isRemoved()
            && !RegioniumPortalDispatcher.isTransferPending(listener.player)
            && !((ServerGamePacketListenerRegioniumAccessorMixin) listener)
                .regionium$isWaitingForSwitchToConfig();
    }
}

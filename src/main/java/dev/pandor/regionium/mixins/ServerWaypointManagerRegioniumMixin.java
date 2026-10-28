package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * ServerWaypointManager owns one world-global Guava Table backed by mutable
 * LinkedHashMaps. Entity movement can call updatePlayer concurrently from
 * different region workers, so all operations which iterate or mutate that
 * shared table must use the same monitor.
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerRegioniumMixin {
    @WrapMethod(method = "trackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void regionium$trackWaypoint(WaypointTransmitter waypoint, Operation<Void> original) {
        synchronized (this) {
            original.call(waypoint);
        }
    }

    @WrapMethod(method = "updateWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void regionium$updateWaypoint(WaypointTransmitter waypoint, Operation<Void> original) {
        synchronized (this) {
            original.call(waypoint);
        }
    }

    @WrapMethod(method = "untrackWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V")
    private void regionium$untrackWaypoint(WaypointTransmitter waypoint, Operation<Void> original) {
        synchronized (this) {
            original.call(waypoint);
        }
    }

    @WrapMethod(method = "addPlayer")
    private void regionium$addPlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (this) {
            original.call(player);
        }
    }

    @WrapMethod(method = "updatePlayer")
    private void regionium$updatePlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (this) {
            original.call(player);
        }
    }

    @WrapMethod(method = "removePlayer")
    private void regionium$removePlayer(ServerPlayer player, Operation<Void> original) {
        synchronized (this) {
            original.call(player);
        }
    }

    @WrapMethod(method = "breakAllConnections")
    private void regionium$breakAllConnections(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "remakeConnections")
    private void regionium$remakeConnections(WaypointTransmitter waypoint, Operation<Void> original) {
        synchronized (this) {
            original.call(waypoint);
        }
    }
}

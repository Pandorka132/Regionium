package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Folia/Paper disables the locator-bar machinery in its server-level waypoint
 * implementation. This is important here: ServerWaypointManager owns a global
 * mutable Guava Table of player/transmitter connections, while Regionium ticks
 * players concurrently on independent region threads.
 *
 * Skipping only updatePlayer() is insufficient. updateWaypoint() can also reach
 * the private updateConnection() method, and that method dereferences the
 * connection before doing any other synchronization. A connection can therefore
 * race with another region and become null between the table lookup and the
 * update. Disable the same feature boundary as Folia/Paper instead of trying to
 * make this global data structure thread-safe from Regionium.
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerRegioniumMixin {
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void regionium$skipGlobalUpdateFromRegionThread(
        ServerPlayer player,
        CallbackInfo ci
    ) {
        if (RegioniumContext.isRegionThread()) {
            Regionium.scheduler().queueWaypointPlayerUpdate(player);
            ci.cancel();
        }
    }

    @Inject(method = "updateWaypoint(Lnet/minecraft/world/waypoints/WaypointTransmitter;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$skipTransmitterUpdateFromRegionThread(
        WaypointTransmitter transmitter,
        CallbackInfo ci
    ) {
        if (RegioniumContext.isRegionThread()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateWaypoint(Lnet/minecraft/world/waypoints/Waypoint;)V", at = @At("HEAD"), cancellable = true)
    private void regionium$skipWaypointUpdateFromRegionThread(
        Waypoint waypoint,
        CallbackInfo ci
    ) {
        if (RegioniumContext.isRegionThread()) {
            ci.cancel();
        }
    }
}

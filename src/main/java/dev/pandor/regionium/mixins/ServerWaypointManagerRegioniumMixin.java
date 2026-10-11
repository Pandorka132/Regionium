package dev.pandor.regionium.mixins;

import net.minecraft.server.waypoints.ServerWaypointManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Intentionally empty and unregistered. Vanilla waypoint handling works
 * without Regionium interception; do not add this mixin back to the config.
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerRegioniumMixin {
}

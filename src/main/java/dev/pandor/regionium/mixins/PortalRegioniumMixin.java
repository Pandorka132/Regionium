package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds Folia's asynchronous portal dispatch entry point to the Portal interface.
 */
@Mixin(Portal.class)
public interface PortalRegioniumMixin {
    default boolean portalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos) {
        return RegioniumPortalDispatcher.startPortalTransferForPortal(
            (Portal) (Object) this, sourceWorld, portalTarget, portalPos
        );
    }
}

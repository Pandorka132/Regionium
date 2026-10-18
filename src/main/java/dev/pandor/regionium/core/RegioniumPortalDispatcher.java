package dev.pandor.regionium.core;

import dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin;
import dev.pandor.regionium.mixins.PortalRegioniumInvokerMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the newly-added Portal.portalAsync method and Entity's region-threaded
 * transfer pipeline without making either Minecraft type depend on mixin classes.
 */
public final class RegioniumPortalDispatcher {
    private static final Set<Entity> PENDING_TRANSFERS = ConcurrentHashMap.newKeySet();

    private RegioniumPortalDispatcher() {
    }

    public static boolean beginTransfer(Entity entity) {
        return PENDING_TRANSFERS.add(entity);
    }

    public static void endTransfer(Entity entity) {
        PENDING_TRANSFERS.remove(entity);
    }

    public static boolean isTransferPending(Entity entity) {
        return PENDING_TRANSFERS.contains(entity);
    }

    public static boolean invokePortalAsync(
        ServerLevel sourceWorld, Entity portalTarget, Portal portal, BlockPos portalPos
    ) {
        return ((PortalRegioniumInvokerMixin) (Object) portal)
            .regionium$invokePortalAsync(sourceWorld, portalTarget, portalPos);
    }

    public static boolean startPortalTransferForPortal(
        Portal portal, ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos
    ) {
        BlockState portalState = sourceWorld.getBlockState(portalPos);
        EntityRegioniumPortalInvokerMixin entityPortal =
            (EntityRegioniumPortalInvokerMixin) (Object) portalTarget;

        if (portal instanceof NetherPortalBlock) {
            ServerLevel destination = sourceWorld.getServer().getLevel(
                sourceWorld.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER
            );
            return destination != null && entityPortal.regionium$invokeStartPortalTransfer(
                portalTarget, sourceWorld, destination, portal, portalPos, portalState
            );
        }

        if (portal instanceof EndPortalBlock) {
            if (sourceWorld.dimension() == Level.END && portalTarget instanceof ServerPlayer player) {
                entityPortal.regionium$invokeExitEndCredits(player);
                return true;
            }
            ServerLevel destination = sourceWorld.getServer().getLevel(
                sourceWorld.dimension() == Level.END ? Level.OVERWORLD : Level.END
            );
            return destination != null && entityPortal.regionium$invokeStartPortalTransfer(
                portalTarget, sourceWorld, destination, portal, portalPos, portalState
            );
        }

        if (portal instanceof EndGatewayBlock) {
            return entityPortal.regionium$invokeStartPortalTransfer(
                portalTarget, sourceWorld, sourceWorld, portal, portalPos, portalState
            );
        }

        return false;
    }
}

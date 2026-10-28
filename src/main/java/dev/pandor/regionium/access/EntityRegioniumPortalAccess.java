package dev.pandor.regionium.access;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;

/** Regionium's typed access to the portal-transfer methods merged into Entity. */
public interface EntityRegioniumPortalAccess {
    void regionium$removeAfterChangingDimensions();

    void regionium$unsetRemoved();

    void regionium$exitEndCredits(ServerPlayer player);

    boolean regionium$startPortalTransfer(
        Entity entity,
        ServerLevel source,
        ServerLevel destination,
        Portal portal,
        BlockPos entry,
        BlockState sourcePortalState
    );

    boolean regionium$startEnderPearlTransfer(
        Entity entity,
        ServerLevel source,
        net.minecraft.world.phys.Vec3 destination,
        boolean spawnEndermite
    );
}

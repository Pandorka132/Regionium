package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityRegioniumPortalInvokerMixin {
    @Invoker("handlePortal")
    void regionium$invokeHandlePortal();

    @Invoker("regionium$startPortalTransfer")
    boolean regionium$invokeStartPortalTransfer(
        Entity entity, ServerLevel source, ServerLevel destination,
        Portal portal, BlockPos entry, BlockState sourcePortalState
    );

    @Invoker("regionium$exitEndCredits")
    void regionium$invokeExitEndCredits(ServerPlayer player);
}

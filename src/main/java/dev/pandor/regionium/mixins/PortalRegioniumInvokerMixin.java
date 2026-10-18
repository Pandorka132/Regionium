package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Portal.class)
public interface PortalRegioniumInvokerMixin {
    @Invoker("portalAsync")
    boolean regionium$invokePortalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos);
}

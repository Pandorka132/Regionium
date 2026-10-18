package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NetherPortalBlock.class)
public interface NetherPortalBlockRegioniumInvokerMixin {
    @Invoker("createDimensionTransition")
    static TeleportTransition regionium$createDimensionTransition(
        ServerLevel level,
        BlockUtil.FoundRectangle rectangle,
        Direction.Axis portalAxis,
        Vec3 relativePosition,
        Entity entity,
        TeleportTransition.PostTeleportTransition postTeleportTransition
    ) {
        throw new AssertionError();
    }
}

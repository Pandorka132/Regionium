package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * A teleport invalidates Entity's cached supporting-block position. Without
 * clearing it, a player can carry a BlockPos from the source region into the
 * destination region and the next movement tick may read that old chunk.
 */
@Mixin(Entity.class)
public abstract class EntityRegioniumTeleportMixin {
    @Inject(method = "teleportTo", at = @At("TAIL"))
    private void regionium$invalidateSupportingBlock(
        ServerLevel level,
        double x,
        double y,
        double z,
        java.util.Set<net.minecraft.world.entity.Relative> relatives,
        float yRot,
        float xRot,
        boolean dismountVehicle,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            Entity entity = (Entity) (Object) this;
            entity.mainSupportingBlockPos = Optional.empty();
        }
    }
}

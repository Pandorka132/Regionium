package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityRegioniumThreadMixin {
    @WrapOperation(
        method = "setPosRaw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onMove()V"
        )
    )
    private void regionium$deferMoveCallback(
        EntityInLevelCallback callback,
        Operation<Void> original
    ) {
        Entity self = (Entity) (Object) this;
        if (!RegioniumContext.isRegionThread()
            || !(self.level() instanceof ServerLevel level)) {
            original.call(callback);
            return;
        }

        // Entity callbacks are region-owned in Folia's model. Do not bounce
        // them through the global server executor: that reintroduces a global
        // entity-management thread and creates stale tracking windows.
        original.call(callback);
        dev.pandor.regionium.Regionium.scheduler().refreshEntityRegion(self);
    }

    @WrapOperation(
        method = "setRemoved",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onRemove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"
        )
    )
    private void regionium$deferRemoveCallback(
        EntityInLevelCallback callback,
        RemovalReason reason,
        Operation<Void> original
    ) {
        Entity self = (Entity) (Object) this;
        if (!RegioniumContext.isRegionThread()
            || !(self.level() instanceof ServerLevel)) {
            original.call(callback, reason);
            return;
        }

        original.call(callback, reason);
        dev.pandor.regionium.Regionium.scheduler().untrackEntity(self);
    }
}

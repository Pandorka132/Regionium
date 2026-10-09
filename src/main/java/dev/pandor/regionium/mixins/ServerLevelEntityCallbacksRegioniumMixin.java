package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * The vanilla entity-section manager remains the lifecycle authority, but its
 * global chunk tracker and global EntityTickList are not region-safe.
 *
 * Folia moves these mutable indexes into regionized world data. Regionium's
 * equivalent local indexes are maintained by the scheduler/world data, so the
 * vanilla global indexes must not be mutated from a region tick.
 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class ServerLevelEntityCallbacksRegioniumMixin {
    @WrapOperation(
        method = "onTickingStart",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;add(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void regionium$skipGlobalTickListAdd(
        EntityTickList list,
        Entity entity,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(list, entity);
        }
    }

    @WrapOperation(
        method = "onTickingEnd",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;remove(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void regionium$skipGlobalTickListRemove(
        EntityTickList list,
        Entity entity,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(list, entity);
        }
    }

    @WrapOperation(
        method = "onTrackingStart",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;addEntity(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void regionium$chunkTrackingAdd(
        ServerChunkCache chunkSource,
        Entity entity,
        Operation<Void> original
    ) {
        // ChunkMap.addEntity() is part of the region-local entity tracking
        // lifecycle. Do not suppress it: suppressing it removes entities from
        // the tracker entirely, which is why clients never receive item
        // entities.
        original.call(chunkSource, entity);
    }

    @WrapOperation(
        method = "onTrackingEnd",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;removeEntity(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void regionium$chunkTrackingRemove(
        ServerChunkCache chunkSource,
        Entity entity,
        Operation<Void> original
    ) {
        original.call(chunkSource, entity);
    }
}

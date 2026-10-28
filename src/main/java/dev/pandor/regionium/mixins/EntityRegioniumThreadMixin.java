package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumTickOffsetAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityRegioniumThreadMixin implements RegioniumTickOffsetAccess {
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

            /*
             * Server-thread teleports are the safe synchronous transfer
             * boundary. The vanilla callback has already updated its global
             * entity-section index, so Regionium can now atomically move the
             * entity to the region owning its new chunk before any region tick
             * can observe the new position.
             */
            Regionium.scheduler().refreshEntityRegion(self);
            // The vanilla callback already updates global chunk tracking on
            // the server thread. Do not enqueue a second ChunkMap.move() for
            // the same position change.
            return;
        }

        // PersistentEntitySectionManager is a global vanilla index. Its
        // EntitySectionStorage contains mutable AVL trees and cannot be
        // modified concurrently by independent region workers.
        //
        // Folia replaces that global lookup with a region-owned entity lookup.
        // Regionium's equivalent source of truth is RegioniumWorldData, so the
        // global onMove callback must not run from a region thread. Entity
        // ownership is reconciled from spatial chunk ownership after the
        // current region tick.
        //
        // Do not call original here: doing so writes the global
        // EntitySectionStorage from the region worker and races other regions.
        // Queue the corresponding ChunkMap.move() for the server thread; it
        // must not run concurrently with another region's chunk tracking.
        if (self instanceof net.minecraft.server.level.ServerPlayer player
            && !dev.pandor.regionium.core.RegioniumPortalDispatcher.isTransferPending(player)) {
            // A portal transfer changes the ServerPlayer's level and position in
            // separate steps, just like Folia's transformForAsyncTeleport. Do
            // not publish an intermediate source-coordinate move into the
            // destination world's global chunk tracker. addDuringTeleport will
            // track the player after the final position has been installed.
            Regionium.scheduler().queueGlobalPlayerMove(player);
        }
        return;
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
            || !(self.level() instanceof ServerLevel level)) {
            original.call(callback, reason);
            return;
        }

        // EntityLookup, PersistentEntitySectionManager, and ChunkMap use vanilla
        // mutable maps which are not safe for concurrent writes from region
        // workers. Removing an entity directly here races chunk-status changes
        // on the server thread and can corrupt their fastutil maps. Detach it
        // from Regionium's owner-local set now, then perform vanilla's callback
        // on the one thread that owns those global indexes.
        Regionium.scheduler().untrackEntity(self);
        level.getServer().execute(() -> original.call(callback, reason));
    }
}

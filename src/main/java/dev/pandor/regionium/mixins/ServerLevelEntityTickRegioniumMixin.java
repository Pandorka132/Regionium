package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * Filters vanilla ServerLevel entity ticking by spatial region ownership.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickRegioniumMixin {
    @Inject(
        method = "tickNonPassenger",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER),
        cancellable = true
    )
    private void regionium$handlePortalAfterEntityTick(Entity entity, CallbackInfo ci) {
        if (entity instanceof net.minecraft.server.level.ServerPlayer || entity.isRemoved()) {
            return;
        }
        ((EntityRegioniumPortalInvokerMixin) (Object) entity).regionium$invokeHandlePortal();
        if (RegioniumPortalDispatcher.isTransferPending(entity)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "tickPassenger",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V", shift = At.Shift.AFTER),
        cancellable = true
    )
    private void regionium$handlePortalAfterPassengerTick(Entity vehicle, Entity entity, CallbackInfo ci) {
        if (entity instanceof net.minecraft.server.level.ServerPlayer || entity.isRemoved()) {
            return;
        }
        ((EntityRegioniumPortalInvokerMixin) (Object) entity).regionium$invokeHandlePortal();
        if (RegioniumPortalDispatcher.isTransferPending(entity)) {
            ci.cancel();
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"
        )
    )
    private void regionium$regionLocalEntityTicks(
        EntityTickList entityTickList,
        Consumer<Entity> vanillaTick
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        var current = RegioniumContext.currentRegion();

        if (current == null) {
            return;
        }

        for (Entity entity : Regionium.scheduler()
            .entitiesForCurrentRegion(level)
            .toArray(Entity[]::new)) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }

            /*
             * Spatial chunk ownership is authoritative during transfer. A
             * player can already have moved to the destination chunk while
             * still being present in the old region's local entity set until
             * the post-tick migration completes.
             */
            if (Regionium.scheduler().regionizer()
                .owner(level, entity.chunkPosition().pack()) != current) {
                continue;
            }

            // Folia only ticks entities whose chunk is in the region's
            // entity-ticking set. Regionium previously ticked every entity
            // belonging to the region, even when its ChunkHolder had not
            // reached ENTITY_TICKING/FULL yet.
            long entityChunkKey = entity.chunkPosition().pack();
            // The holder's entity-ticking future is the published status for
            // this chunk. Do not query DistanceManager from a region worker:
            // its SimulationChunkTracker maps are mutable server-thread state.
            var holder = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
                (Object) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkMap()
                .get(entityChunkKey);
            if (holder == null
                || holder.getEntityTickingChunkFuture()
                    .getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null) == null) {
                continue;
            }

            if (entity instanceof net.minecraft.server.level.ServerPlayer) {
                // ServerPlayer is ticked by ServerGamePacketListenerImpl.tick(),
                // which runs as part of the owning Connection.tick() after the
                // world tick.
                // Folia keeps players in RegionizedWorldData.localPlayers rather
                // than the region entity-tick collection; ticking them here as
                // well would execute player.doTick() twice per server tick.
                continue;
            }

            vanillaTick.accept(entity);
        }
    }
}

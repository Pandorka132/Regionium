package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * Filters vanilla ServerLevel entity ticking by spatial region ownership.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickRegioniumMixin {
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
            var holder = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
                (Object) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkMap()
                .get(entity.chunkPosition().pack());
            if (holder == null
                || holder.getEntityTickingChunkFuture()
                    .getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null) == null) {
                continue;
            }

            if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                // A disconnect can clear the connection before the entity is
                // removed from the region-owned entity set. Vanilla
                // ServerPlayer.tick assumes a live connection, so lifecycle
                // ownership must exclude the player for that tick.
                if (player.connection == null
                    || !Regionium.scheduler().arePlayerPhysicsChunksReady(level, player)) {
                    continue;
                }
            }

            vanillaTick.accept(entity);
        }
    }
}

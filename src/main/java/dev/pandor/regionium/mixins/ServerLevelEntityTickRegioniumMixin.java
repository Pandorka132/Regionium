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
 * Filters vanilla ServerLevel entity ticking by the current region.
 *
 * ServerLevel.tick() is now executed directly by a Regionium region clock,
 * so the entity phase must stay synchronous with that tick rather than
 * enqueueing another mailbox task.
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
            // Entity execution belongs to region-local world data. The global
            // ServerLevel maintenance pass must never tick the shared list.
            return;
        }

        // Folia-style: the region tick iterates its region-local entity index,
        // never the shared vanilla EntityTickList.
        for (Entity entity : Regionium.scheduler().entitiesForCurrentRegion(level).toArray(Entity[]::new)) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (Regionium.scheduler().chunkLeases().owner(level, entity.chunkPosition().pack()) != current) {
                Regionium.scheduler().refreshEntityRegion(entity);
                continue;
            }
            vanillaTick.accept(entity);
        }
    }
}

package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapRegioniumTickInvokerMixin {
    /**
     * Folia calls only the entity-tracker pass from a region tick. The
     * complete ChunkMap.tick() also performs global chunk unload/save
     * maintenance and therefore must remain on the server maintenance path.
     */
    @Invoker("tick")
    void regionium$invokeTrackerTick();
}

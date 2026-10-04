package dev.pandor.regionium.mixins;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimulationChunkTracker.class)
public interface SimulationChunkTrackerRegioniumAccessorMixin {
    @Accessor("chunks")
    Long2ByteMap regionium$getChunks();
}

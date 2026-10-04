package dev.pandor.regionium.mixins;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerRegioniumAccessorMixin {
    @Accessor("simulationDistance")
    int regionium$getSimulationDistance();

    @Accessor("simulationChunkTracker")
    SimulationChunkTracker regionium$getSimulationChunkTracker();

    @Accessor("playersPerChunk")
    Long2ObjectMap<ObjectSet<ServerPlayer>> regionium$getPlayersPerChunk();
}

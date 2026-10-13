package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Serializes scheduled tick delays relative to the owning region's redstone
 * clock, while keeping the chunk's ordinary world-time metadata global.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataRegioniumTimeMixin {
    @WrapOperation(
        method = "copyOf",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getTicksForSerialization(J)Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;"
        )
    )
    private static ChunkAccess.PackedTicks regionium$packTicksAtRegionTime(
        ChunkAccess chunk,
        long ignoredGlobalTime,
        Operation<ChunkAccess.PackedTicks> original,
        ServerLevel level,
        ChunkAccess ignoredChunk
    ) {
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(
            level, chunk.getPos().pack()
        );
        long redstoneTime = owner == null
            ? level.getLevelData().getGameTime()
            : owner.worldData().redstoneTime();
        return original.call(chunk, redstoneTime);
    }
}

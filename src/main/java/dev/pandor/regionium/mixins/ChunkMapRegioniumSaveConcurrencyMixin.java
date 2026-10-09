package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunk serialization is a snapshot operation. Region threads may mutate the
 * same LevelChunk while the server save path is copying it, so the snapshot
 * must use the same per-chunk monitor as block/entity mutations.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumSaveConcurrencyMixin {
    @WrapOperation(
        method = "save",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"
        )
    )
    private SerializableChunkData regionium$atomicChunkSnapshot(
        ServerLevel level,
        ChunkAccess chunk,
        Operation<SerializableChunkData> original
    ) {
        if (chunk instanceof LevelChunk levelChunk) {
            synchronized (levelChunk) {
                return original.call(level, chunk);
            }
        }
        return original.call(level, chunk);
    }
}

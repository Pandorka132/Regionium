package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Chunk serialization is a snapshot operation. Never make the global server
 * thread wait for a region tick: when the ownership barrier is busy, defer this
 * save attempt and let vanilla retry it on a later save pass. This keeps the
 * snapshot consistent without letting one slow region stall command dispatch.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumSaveConcurrencyMixin {
    @WrapMethod(method = "save")
    private boolean regionium$nonBlockingChunkSave(
        ChunkAccess chunk,
        Operation<Boolean> original
    ) {
        if (!(chunk instanceof LevelChunk)) {
            return original.call(chunk);
        }

        boolean snapshotBarrier = Regionium.scheduler().regionizer().enterChunkSaveSnapshot();
        if (!snapshotBarrier) {
            return false;
        }
        try {
            return original.call(chunk);
        } finally {
            Regionium.scheduler().regionizer().exitChunkSaveSnapshot();
        }
    }
}

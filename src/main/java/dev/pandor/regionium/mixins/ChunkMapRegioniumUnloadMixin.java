package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ChunkMap's server-thread unload pass from invalidating a chunk
 * while a Regionium worker is still executing that chunk's tick callback.
 *
 * This is a chunk lifecycle guard, not a redstone-specific patch.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumUnloadMixin {
    @Shadow @Final private LongSet toDrop;
    @Shadow @Final private ServerLevel level;

    private final ThreadLocal<LongArrayList> regionium$deferredDrops =
        ThreadLocal.withInitial(LongArrayList::new);

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void regionium$protectActiveChunks(
        java.util.function.BooleanSupplier haveTime,
        CallbackInfo ci
    ) {
        LongArrayList deferred = regionium$deferredDrops.get();
        deferred.clear();

        for (long chunkPos : toDrop) {
            if (Regionium.scheduler().isChunkExecutionActive(level, chunkPos)) {
                deferred.add(chunkPos);
            }
        }

        for (int i = 0; i < deferred.size(); i++) {
            toDrop.remove(deferred.getLong(i));
        }
    }

    @Inject(method = "processUnloads", at = @At("RETURN"))
    private void regionium$restoreActiveChunkDrops(
        java.util.function.BooleanSupplier haveTime,
        CallbackInfo ci
    ) {
        LongArrayList deferred = regionium$deferredDrops.get();
        for (int i = 0; i < deferred.size(); i++) {
            long chunkPos = deferred.getLong(i);
            if (Regionium.scheduler().isChunkExecutionActive(level, chunkPos)) {
                toDrop.add(chunkPos);
            }
        }
        deferred.clear();
    }
}

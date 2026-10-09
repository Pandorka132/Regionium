package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents a region tick from entering vanilla's global synchronous chunk
 * loading path. Folia's chunk system makes this same boundary: a region tick
 * may only access chunk state already prepared by the chunk lifecycle.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegioniumThreadMixin {
    @Shadow private ServerLevel level;
    @Shadow public ChunkMap chunkMap;

    @Inject(
        method = "getChunk",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$ownedChunkOnly(
        int x,
        int z,
        ChunkStatus targetStatus,
        boolean loadOrGenerate,
        CallbackInfoReturnable<ChunkAccess> cir
    ) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        var current = RegioniumContext.requireRegionThread();
        long key = net.minecraft.world.level.ChunkPos.pack(x, z);
        var owner = Regionium.scheduler().regionizer().owner(level, key);

        /*
         * Vanilla deliberately calls getChunk(..., false) from a number of
         * "is this chunk already loaded?" paths (for example fluid/entity
         * interaction). Those calls must retain vanilla's non-loading
         * semantics: an unavailable chunk is represented by null. Throwing
         * here turns an ordinary neighbour check into a failed region tick.
         *
         * A region is still forbidden from synchronously loading another
         * region's chunk. Only an already prepared chunk may be returned.
         */
        if (owner != current) {
            if (!loadOrGenerate) {
                cir.setReturnValue(null);
                return;
            }

            throw new IllegalStateException(
                "Region " + current.id() + " attempted to synchronously load chunk "
                    + x + "," + z + " owned by " + owner
            );
        }

        ChunkHolder holder = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) chunkMap)
            .regionium$getVisibleChunkMap().get(key);
        ChunkAccess chunk = holder == null ? null : holder.getChunkIfPresent(targetStatus);
        if (chunk == null) {
            if (!loadOrGenerate) {
                cir.setReturnValue(null);
                return;
            }

            throw new IllegalStateException(
                "Region " + current.id() + " attempted to synchronously load chunk "
                    + x + "," + z + " at status " + targetStatus
            );
        }

        cir.setReturnValue(chunk);
    }
}

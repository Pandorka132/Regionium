package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes vanilla ServerChunkCache thread-affinity checks Regionium-aware
 * without changing the cache's global mainThread field.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegioniumThreadMixin {
    @Shadow private Thread mainThread;

    @WrapOperation(
        method = {"getChunk", "getChunkNow", "getChunkFuture"},
        at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;")
    )
    private Thread regionium$logicalCurrentThread(Operation<Thread> original) {
        return RegioniumContext.isRegionThread() ? mainThread : original.call();
    }
}
